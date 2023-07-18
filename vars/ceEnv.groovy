import jenkins.model.Jenkins;
import groovy.transform.Field

def isAuthorized(groupName) {
    wrap([$class: 'BuildUser']) {
        userName = env.BUILD_USER_ID
        logHandler.debug("userName: ${userName}")
    }
    if (userName) {  // For automated builds, userName will be null
        isAuthorized(groupName, userName)
    } else {
        return false
    }
}

def isAuthorized(groupName, userName) {
    def auth = getSingleSSMParameter("jenkins-api-credentials", "qa")
    def resp = sh(returnStdout:true, script: "#!/bin/sh -e\n curl -u \"${auth}\" \
        -X GET \
        -H 'Accept: application/json' \
        -H 'Content-Type: application/json' \
        -d '{}' \
        \"https://jenkins.cccnext.net/role-strategy/strategy/getRole?type=globalRoles&roleName=${groupName}\"")
    if (!resp || resp == '') {
        logHandler.error("No response received from Jenkins for getRole")
        return null
    }
    if (resp.contains(userName)) {
        return true
    } else {
        return false
    }
}

def isBuildUserAuthorized(prod_deploy_group = 'admin-deploy') {
    wrap([$class: 'BuildUser']) {
        userName = env.BUILD_USER_ID
    }
    if (userName) {  // For automated builds, userName will be null
        logHandler.debug("userName was not null")
        isAuthorized(prod_deploy_group, userName)
    } else {
        return false
    }
}

def isGitUserAuthorized(prod_deploy_group = 'admin-deploy') {
    userName = sh (
        script: 'git --no-pager show -s --format=\'%cn\'',
        returnStdout: true
    ).trim()
    logHandler.debug("git username: ${userName}")
    userEmail = sh (
        script: 'git --no-pager show -s --format=\'%ce\'',
        returnStdout: true
    ).trim()
    logHandler.debug("git user email: ${userEmail}")
    return isAuthorized(prod_deploy_group, userName) || isAuthorized(prod_deploy_group, userEmail)
}

def isAuthorizedForEnv(environment, prod_deploy_group = 'admin-deploy') {
    if ((environment == "pilot" || environment == "prod") && !(isBuildUserAuthorized() || isGitUserAuthorized())) {
        return false
    }
    return true
}

def testAuthorization() {
    try {
        if (isBuildUserAuthorized()) {
            logHandler.info("This build user WOULD be authorized for a pilot/prod deploy")
        } else {
            logHandler.info("This build user WOULD NOT be authorized for a pilot/prod deploy")
        }
        if (isGitUserAuthorized()) {
            logHandler.info("This git user WOULD be authorized for a pilot/prod deploy")
        } else {
            logHandler.info("This git user WOULD NOT be authorized for a pilot/prod deploy")
        }
    } catch (Exception e) {
        print "ERROR: " + e.toString()
    }
}

def getVersionTags() {
   sh(returnStdout: true, script: "git tag | grep -o '[0-9]*\\.[0-9]*\\.[0-9].*' | sort --version-sort -r").trim()
}

def getLatestTag() {
   sh(returnStdout: true, script: "git tag | grep -o '[0-9]*\\.[0-9]*\\.[0-9].*' | sort --version-sort | tail -1").trim()
}

def getLatestTag(repo) { // Get the most recent tag on the supplied repo, (sorted numerically)
    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: '63b0b64f-edba-40cb-9c1e-c94a6f78c6e8', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD']]) {
        sh(returnStdout: true, script: "git ls-remote --tags https://${env.GIT_USERNAME}:${env.GIT_PASSWORD}@bitbucket.org/cccnext/${repo}.git | cut -f2 | sort --version-sort | tail -1 | grep -Po '(?<=refs/tags/)[^^]+'").trim()
    }
}

// If we're running a release branch, insure that the git tag exists.
def validateGitTag(tag) {
    if (tag =~ /\d+\.\d+\.\d+/) {
        def exit_code = sh(script: "git show ${tag}", returnStatus: true)
        if (exit_code) {
            logHandler.error("Version tag ${tag} does not exist in Git")
            logHandler.error("Version tag ${tag} does not exist in Git - add before proceeding with build")
            error "Version tag ${tag} does not exist in Git - add before proceeding with build"
        }
    }
}

def requestAuth(service, environment, channel, group) {
    latest_tag = getLatestTag()
    version_tags = getVersionTags()
    if ((env.deploy_tag != null) && (env.deploy_tag != latest_tag)) {
       tags = env.deploy_tag + "\n" + version_tags
    } else {
       env.deploy_tag = latest_tag
       tags = version_tags
    }
    slackSend channel: channel, color: 'E320D1', message: "Approval requested for ${environment} deploy of ${service}:${env.deploy_tag} \nLink: ${env.BUILD_URL}input"
    output = input(id: 'UserInput', message: 'User input required', ok: "Deploy ${service} to ${environment}!", parameters: [choice(choices: tags, description: 'choose the version to deploy', name: 'version')], submitterParameter: 'submitter')
    user = output['submitter']
    env.deploy_tag = output['version']
    if (isAuthorized(group, user)) {
        slackSend channel: channel, color: 'A4F305', message: "Approval granted for ${environment} deploy of ${service}:${env.deploy_tag} by user ${user}\nLink: ${env.BUILD_URL}"
        return true
    } else {
        logHandler.error("Not building - user not in group ${group}")
        slackSend channel: channel, color: '751F1F', message: "Approval denied for ${environment} deploy of ${service}:${env.deploy_tag} by user ${user}\nLink: ${env.BUILD_URL}"
        currentBuild.result = "ABORTED"
        logHandler.error("Not Deploying: User ${user} not authorized for deployments")
        error "Not Deploying: User ${user} not authorized for deployments"
    }
}

def getEnvProperties(environment) {
    return getEnvProperties("course-exchange", environment)
}

def getEnvProperties(stack, environment) {
    filename = "org/ccctc/${stack}/${environment}.properties"
    return getEnvProperties("ccctc",stack, environment)
}

def getEnvProperties(org,stack, environment) {
    filename = "org/${org}/${stack}/${environment}.properties"
    logHandler.debug("loading filename: $filename")
    env_string = libraryResource filename
    logHandler.debug("properties for build:\n$env_string")

    Properties props = new Properties()
    props.load(new ByteArrayInputStream(env_string.getBytes()))
    return props
}

def getLocalProperties(filename) {
    logHandler.debug("loading filename: $filename")
    env_string = new File(filename).text
    logHandler.debug("properties for build:\n$env_string")

    Properties props = new Properties()
    props.load(new ByteArrayInputStream(env_string.getBytes()))
    return props
}

def getLocalProperties(org,stack,environment) {
    // this assumes a folder containing repo called <${stack}-deploy
    // see openmdm-deploy repo for deployment steps and properties files
    filename = "${stack}-deploy/resources/org/${org}/${stack}/${environment}.properties"
    return getLocalProperties(filename)
}

def getServiceProp(service) {
    def service_prop
    switch (service) {
        case "logio-logs":                      service_prop = "logio"; break
        case "logio-server":                    service_prop = "logio"; break
        case "logspout":                        service_prop = "logspout"; break
        case "docker-gc":                       service_prop = "docker-gc"; break
        case "postgres":                        service_prop = "postgres"; break
        case "college-adaptor-router":          service_prop = "router"; break
        case "courseexchange-jasper-reports":   service_prop = "jasper"; break
        case "courseexchange-api":              service_prop = "api"; break
        case "courseexchange-admin":            service_prop = "admin"; break
        case "courseexchange-oauth":            service_prop = "oauth"; break
        case "courseexchange-student-web":      service_prop = "student"; break
        case "college-adaptor":                 service_prop = "adaptor"; break
        case "collegeldap":                     service_prop = "collegeldap"; break     //open mdm
        case "elk":                             service_prop = "elk"; break             //open mdm
        case "filebeat":                        service_prop = "filebeat"; break        //open mdm
        case "idp":                             service_prop = "idp"; break             //open mdm
        case "ldapadmin":                       service_prop = "ldapadmin"; break       //open mdm
        case "openmdm-amq":                     service_prop = "openmdm-amq"; break     //open mdm
        case "openmdm-api":                     service_prop = "openmdm-api"; break     //open mdm
        case "openmdm-db":                      service_prop = "openmdm-db"; break      //open mdm
        case "younite-ui":                      service_prop = "younite-ui"; break      //open mdm
        case "network":                         service_prop = "network"; break
        case "directory":                       service_prop = "directory"; break
        case "network-db":                      service_prop = "network.db"; break
        case "directory-db":                    service_prop = "directory.db"; break
        case "newrelic-java-agent":             service_prop = "newrelic-java-agent"; break
        case "test-mapping-container":          service_prop = "test-mapping-container"; break
        case "administrator2":                  service_prop = "admin-ui"; break
        case "administrator2-api":              service_prop = "api"; break
        case "administrator2-db":               service_prop = "db"; break
        default:                                service_prop = service; break
    }

    return service_prop
}

def getEnvironmentFromBranchName(branch, project) {
    def environment
    switch (branch) {
        case ~/feature.*/:
        case ~/hotfix.*/:
        case ~/bugfix.*/:
        case ~/PR.*/:
            if (project =~ /college-adaptor-router/) {
                environment = "qa"
            } else if (project == "elk-services") {
                environment = "dev"
            } else {
                environment = "ci"
            }
            break
        case "stable":
        case "version-upgrade" :
        case ~/publish.*/:
        case "develop":
            if (project =~ /apply-submit/ || project =~ /apply-services/ || project =~ /apply-ml-services/ || project =~ /openccc.*/ || project =~ /mypath.*/ || project =~ /helpdesk/) {
                environment = "test"
            } else if (project == "cloud-config-service") {
                environment = "ci"
            } else if (project == "elk-services") {
                environment = "dev"
            } else {
                environment = "qa"
            }
            break
        case ~/release.*/:
            if (project == "course-exchange" || project == 'apply-services') {
                environment = "test"
            } else if (project == "cloud-config-service") {
                environment = "ci"
            } else {
                environment = "pilot"
            }
            break
        case "master":
            environment = "prod"
            break
        default:
            environment = null
            break
    }
    if (environment == null) { error "Unsupported branch name :"  + env.BRANCH_NAME }
    return environment
}
def getEnvironmentFromBranchName(branch) {
    getEnvironmentFromBranchName(branch, "course-exchange")
}

def getAdaptorEnvironmentFromBranchName(branch) {
    getEnvironmentFromBranchName(branch, "adaptor")
}

def getAwsAccountId(aws_env) {
    switch (aws_env) {
        case "mypath-ci":       account_id = "035922915890"; break
        case "mypath-test":     account_id = "318779739605"; break
        case "mypath-pilot":    account_id = "742482887854"; break
        case "mypath-prod":     account_id = "742482887854"; break
        case "openccc-ci":      account_id = "437039948125"; break
        case "openccc-test":    account_id = "437039948125"; break
        case "openccc-pilot":   account_id = "192347470102"; break
        case "openccc-prod":    account_id = "599199943077"; break
        case "apply-ci":        account_id = "437039948125"; break
        case "apply-qa":
        case "apply-test":
        case "apply-nonprod":   account_id = "437039948125"; break
        case "apply-pilot":     account_id = "192347470102"; break
        case "apply-prod":      account_id = "599199943077"; break
        case "exchange-ci":
        case "superglue-ci":
        case "superglue-qa":
        case "dw-ci":
        case "ci":
        case "sss-ci":
        case "sss-qa":
        case "sss-test":
        case "dw-qa":
        case "exchange-qa":
        case "test":
        case "qa":              account_id = "788941008930"; break
        case "dw-pilot":
        case "exchange-pilot":
        case "openccc-pilot":
        case "superglue-pilot":
        case "sss-pilot":
        case "pilot":           account_id = "975082606358"; break
        case "exchange-prod":
        case "openccc-prod":
        case "superglue-prod":
        case "sss-prod":
        case "prod":            account_id = "157477594776"; break
        case "dw-prod":         account_id = "724382154229"; break
        case ~/^[0-9]+$/:       account_id = aws_env; break // support directly passing in account_id
        default:                account_id = "157477594776"; break
    }
    logHandler.debug("account_id ${account_id}")
    logHandler.debug("account: ${aws_env}")
    return account_id
}

def setSSMCreds(account, role = 'AllowAccessFromJenkinsBuildServersToSSMParameters') {
    account_id = getAwsAccountId(account)
    setAWSCreds("arn:aws:iam::"+account_id+":role/"+role)
}

def setSSMCreds() {
    setSSMCreds("exchange-prod")
}

def unsetSSMCreds() { //called after accessing any needed params in order to revert to full aws access via EC2 host role vs through the limited assume-role
    env.AWS_ACCESS_KEY_ID = ''
    env.AWS_SECRET_ACCESS_KEY = ''
    env.AWS_SESSION_TOKEN = ''
}

def getSSMParameter(param, options) {
    retval = sh(returnStdout: true, script: "aws ssm get-parameters --names ${param} ${options} --query 'Parameters[0].Value' --output text").trim()
    if (retval == "None") {
        slackSend channel: "#devops", color: "0DE8D4", message: "ERROR: attempt to retrieve value from SSM for $param was not successful. \nInsure that the value is set in the account used by setSSMCreds(). \nJob: ${env.BUILD_URL}"
        error "ERROR: attempt to retrieve value from SSM for $param was not successfull\nInsure that the value is set in the account used by setSSMCreds()"
    } else {
        return retval
    }
}

def getSSMParameter(param) {
    getSSMParameter(param, "--with-decryption")
}

def getSingleSSMParameter(param, env) { //include setting and unsetting creds
    setSSMCreds(env)
    value = getSSMParameter(param)
    unsetSSMCreds()
    return value
}

def setAWSCreds(role) { //assume an aws role
    sh '''aws sts assume-role \
            --role-arn ''' + role + ''' \
            --role-session-name get-parameters > .creds'''
    env.AWS_ACCESS_KEY_ID = sh(returnStdout: true, script: "cat .creds | jq -r '.Credentials.AccessKeyId'").trim()
    env.AWS_SECRET_ACCESS_KEY = sh(returnStdout: true, script: "cat .creds | jq -r '.Credentials.SecretAccessKey'").trim()
    env.AWS_SESSION_TOKEN = sh(returnStdout: true, script: "cat .creds | jq -r '.Credentials.SessionToken'").trim()
    env.AWS_DEFAULT_REGION = "us-west-2"
    sh "rm .creds"
}

def setAWSCreds(approver, token, accountId) { //assume an aws role
    credentialsId="${approver}_jenkins"
    env.AWS_DEFAULT_REGION = "us-west-2"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: credentialsId]]) {
        listMfaCommand = $/aws iam list-virtual-mfa-devices | jq -r '.VirtualMFADevices[] | select(.User.UserName=="${credentialsId}") | .SerialNumber'/$
        mfaSerial = sh(returnStdout: true, script: listMfaCommand).trim()
        sh """aws sts assume-role \
            --role-arn arn:aws:iam::${accountId}:role/CCCNextCloudformationAccess \
            --role-session-name stack-updates \
            --serial-number ${mfaSerial} \
            --token-code ${token} > .creds"""
    }

    env.AWS_ACCESS_KEY_ID = sh(returnStdout: true, script: "cat .creds | jq -r '.Credentials.AccessKeyId'").trim()
    env.AWS_SECRET_ACCESS_KEY = sh(returnStdout: true, script: "cat .creds | jq -r '.Credentials.SecretAccessKey'").trim()
    env.AWS_SESSION_TOKEN = sh(returnStdout: true, script: "cat .creds | jq -r '.Credentials.SessionToken'").trim()
    sh "rm .creds"
}

def getMFAInput(aws_env) {
    output = input(id: 'MFAInput', message: 'Valid MFA Token Required', ok: "Create/Update Stacks", parameters: [[$class: 'StringParameterDefinition', description: 'enter a MFA token', name: 'mfa']], submitterParameter: 'submitter')
    setAWSCreds(output['submitter'], output['mfa'], getAwsAccountId(aws_env))
}

def requestAwsCredentialsViaRocketChat(service, environment, channel) {
    rocketSend attachments: [[text: "[Click Here](${env.BUILD_URL}input)", title: 'Approval Link']], channel: channel, message: "Approval requested for deploy of ${service} to ${environment}", rawMessage: true, emoji: ":white_check_mark:"
    getMFAInput("${service}-${environment}")
}

def requestAwsCredentialsViaSlack(service, environment, channel) {
    slackSend channel: channel, color: 'E320D1', message: "Approval requested for deploy of ${service} to ${environment}\nLink: ${env.BUILD_URL}input"
    getMFAInput("${service}-${environment}")
}

def setupMypathDeployEnv(Map config) {
    environment = getEnvironmentFromBranchName(env.BRANCH_NAME, "mypath")
    if (!environment) { error "Unsupported branch name: " + env.BRANCH_NAME }

    env.UTC_TIMESTAMP = sh(returnStdout: true, script: 'date +"%s"').trim()
    env.AWS_ENV = environment
    env.S3BUCKET = "ccc-${environment}-cf-templates"
    env.AWS_DEFAULT_PROFILE = "${environment}"

    def devRancher = ['ci','test', 'qa']
    env.RANCHER_URL = (AWS_ENV in devRancher ) ? "https://rancher.dev.ccctechcenter.org" : "https://rancher-server.ccctechcenter.org"
    env.RANCHER_KEY = sh(returnStdout: true, script: """aws --profile=${AWS_ENV} --region=us-west-2 ssm get-parameter --name rancher-key-mypath-${AWS_ENV} --with-decryption | jq -rj '.Parameter.Value'""")
    env.RANCHER_PASS = sh(returnStdout: true, script: """aws --profile=${AWS_ENV} --region=us-west-2 ssm get-parameter --name rancher-pass-mypath-${AWS_ENV} --with-decryption | jq -rj '.Parameter.Value'""")
    config.rancher_key = env.RANCHER_KEY
    config.rancher_pass = env.RANCHER_PASS
    config.rancher_url = env.RANCHER_URL
    config.environment = env.AWS_ENV
    config.channel = env.channel
    config.timeout = 10
}
