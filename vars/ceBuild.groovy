//build functions
import groovy.transform.Field

//global variables
@Field registry_url = "registry.ccctechcenter.org:5000"
@Field sonarqube_url = "https://sonarqube.ccctechcenter.org"
@Field sonarqube_env = "sonarqube-prod.ccctechcenter.org" //This defines which credential to pull from Jenkins
@Field run_checkmarx_scan = false //toggle scan on or off
@Field run_checkmarx_async = false //run asyncronously (don't block job waiting for results)
@Field run_checkmarx_incremental = true //toggle incremental vs. full scan
@Field service = null //toggle incremental vs. full scan

def setupEnv(jdk) {
    def mvnHome = tool 'M3.6.3'
    env.M2_HOME = "${mvnHome}"
    env.PATH = "${mvnHome}/bin:${env.PATH}"
    env.JAVA_HOME = "${jdk}"
    env.PATH = "${jdk}/bin:${env.PATH}"
    logHandler.debug("PATH: $PATH")
    logHandler.debug("JAVA_HOME: $JAVA_HOME")
    logHandler.debug("java version:")
    def ret = sh(script: 'java -version', returnStdout: true)
    logHandler.debug("${ret}")
    logHandler.debug("M2_HOME: $M2_HOME")
}

def setupEnv(int version=8) {
    if (version == 8) {
        jdk = tool name: 'openjdk-8-jdk'
    } else if ( version == 11) {
        jdk = tool 'Java 11'
    } else if ( version == 15) {
        jdk = tool 'Java 15'
    } else {
       logHandler.error("Unknown/not-installed java version")
       error "Unknown/not-installed java version"
    }
    setupEnv(jdk)
}

def mvnBuild(command, int version=8) {
    setupEnv(version)
    try {
        sh command //should typically only fail if there are build failures, as caller should set -Dmaven.test.failure.ignore=true
        currentBuild.result = 'SUCCESS'
    } catch (Exception err) {
        currentBuild.result = 'FAILURE'
    }
    logHandler.info("Build RESULT: ${currentBuild.result}")
    jacoco() //publish coverage report, if available
    if (env.BRANCH_NAME == "develop") {
        checkmarx_service = service ?: "unknown" //not all projects export env.service - this prevents unknown property setting.  Add a warning here so we catch this and can go back and update those projects
        cm_scan = checkMarxScan(run_checkmarx_incremental, run_checkmarx_scan, run_checkmarx_async, checkmarx_service)
    }
    return currentBuild.result //support some legacy forms of this method
}

def mvnBuild(command, service, int version=8) {
    return mvnBuild(command, service, "cccnext", version)
}

def mvnBuild(command, service, account, version=8) {

    if (env.BRANCH_NAME == "develop") {

    //FIXME: PR scans are not functional unless we upgrade to Sonarqube developer edition. Reference DEVOPS-975.Disabling.
    //if (env.BRANCH_NAME =~ /PR-.*/ || env.BRANCH_NAME == "develop") {
        setupEnv(version)
        if (env.BRANCH_NAME == "develop") {
            checkmarx_service = service ?: "unknown" //not all projects export env.service - this prevents unknown property setting.  Add a warning here so we catch this and can go back and update those projects
            cm_scan = checkMarxScan(run_checkmarx_incremental, run_checkmarx_scan, run_checkmarx_async, checkmarx_service)
        }
        repo_name = scm.getUserRemoteConfigs()[0].getUrl().tokenize('/')[3].split("\\.")[0]
        return sonarScan(repo_name, account, command)
    } else {
        return mvnBuild(command, version)
    }
}

def dockerPush(image, tag) {
  dockerPush(image, "latest", tag)
}

def dockerPush(image, source_tag, target_tag) {
  dockerPush(image, source_tag, target_tag, "ccctechcenter.org")
}

def dockerPush(image, source_tag, target_tag, domain) {
  registry_url =  "registry.${domain}:5000"
  try {
    retry(5) {
      docker.withRegistry("https://${registry_url}", '00733feb-a287-4875-94ba-e6b5c16c56a8') {
        sh "docker tag ${image}:${source_tag} ${registry_url}/${image}:${target_tag}"
        if (target_tag != null) {
          sh "docker push ${registry_url}/${image}:${target_tag}"
        }
      }
    }
  } catch (Exception e) {
    slackSend channel: "#devops" , color: "0DE8D4", message: "Exception authenticating to docker registry ${registry_url}\nJob:  ${env.BUILD_URL}" + e.getMessage()
    logHandler.error("Error pushing to docker registry: " + e.toString())
    error "Error pushing to docker registry: " + e.toString()
  }
}

def getDeployTag(branch) {
    getDeployTag("course-exchange", branch)
}

def getDeployTag(stack, branch) {
    def deploy_tag
    switch (branch) {
        case ~/version-upgrade.*/: // used for end of sprint reporting
        case ~/publish.*/:
        case ~/feature.*/:
        case ~/migrate.*/:
        case ~/bugfix.*/:
        case ~/release.*/:
        case ~/hotfix.*/:
            deploy_tag = this.parseTagFromBranch(branch)
            break
        case ~/PR.*/: //special "pull-request" branch type that gets created automatically
            if ((env.CHANGE_BRANCH =~ /release.*/ || env.CHANGE_BRANCH =~ /hotfix.*/ || env.CHANGE_BRANCH =~ /publish.*/) && env.CHANGE_TARGET == "master" ) { //if the source branch of the PR is a "release" version and the target is master, abort.
                //deploy_tag = this.parseTagFromBranch(env.CHANGE_BRANCH)
                currentBuild.result = 'NOT_BUILT'
                error('Not building release branch PR builds where target is master. This is generally the normal result of our workflow.  If you have updated a release branch after opening a pull-request, and require the release tag to be rebuilt, please cancel the pull-request before attempting to build again')
            } else {
                deploy_tag = this.parseTagFromBranch(branch)
            }
            break
        case "develop":
            deploy_tag = "latest"
            break
        case "stable":
            deploy_tag = "stable"
            break
        case "master":
            if (stack == "course-exchange") {
                deploy_tag = null //deploy not supported for master branch
            } else if (stack == "api-gateway-test" ) { //only using latest or master tags there
              deploy_tag = "released"
            } else { //get the latest tag off of the master branch, use to sync + deploy
                deploy_tag = sh(returnStdout: true, script: "git tag | grep -o '[0-9]*\\.[0-9]*\\.[0-9]*' | sort --version-sort | tail -1").trim()
            }
            break
        default:
            deploy_tag = null
            break
    }
    return deploy_tag
}

//Deprecated
def getEnvTag(branch) {
    return this.getDeployTag(branch)
}

//Deprecated
def getBranchTag(branch) {
    return this.getDeployTag(branch)
}

// Insure we create a docker compliant tag. Create a tag based on a version, if it's available. Otherwise use the branch name, minus first part (before the first /)
// NOTE: external caller should use the getDeployTag function, rather than calling this directly
def parseTagFromBranch(branch) {
    def tag = null
    if (branch =~ /[aA-zZ].*\/[aA-zZ].*-[0-9].*/) { //for all feature/CE-XXX or bugfix/CE-XXX branches, return a tag CE_XXX. Throwaway extra characters after the last numeral
      tag = branch.replaceAll(/[aA-zZ]*\/([aA-zZ]*)-([0-9]*)(.*)/, '$1_$2')
    } else { //make sure we set a valid tag in other non-standard cases
      tag = branch.replaceAll(/-/, '_')
      tag = tag.replaceAll(/[aA-zZ]*\/(.*)/, '$1')
    }
    return tag
}

def getGitsha() {
    sh "git rev-parse --short HEAD > .git/commit-id"
    commit_id = readFile('.git/commit-id').trim()
    logHandler.debug("got commit_id: $commit_id")
    return commit_id
}

def checkoutSCM() {
    logHandler.warn("checkoutSCM is deprecated: Git tag pull functionality is provided by Jenkins configuration - advanced clone behaviors")
}

// add support for updating submodules after a build and tests passed on develop branch YOUMDM-397 update local-mdm-compose variables
// TODO: currently use a SECRET file in local_compose_repo_file_credentials to commit back to
// the repo.  We have an existing ssh username / secret file but haven't figured out how to make
// that work with the WithCredentials() method
// PARAMS:
// repo_folder            -  directory to checkout repo
// repo_url               -  url for repo checkout
// repo_credentials          -  jenkins credentials id (ssh username with key)
// repo_branch              -  branch to check out
// repo_file_credentials  - private key to use when pushing commit back to the repo
// submodule_path         - path to submodule from root of repository
// gitsha                 - submodule commit to check out
// message                - commit message
def updateRepoSubmodulePointer(repo_folder,repo_url,repo_credentials,repo_branch,repo_file_credentials,submodule_path,gitsha,message)
{
    checkout changelog: false, poll: false, scm: [$class: 'GitSCM', branches: [[name: repo_branch]], doGenerateSubmoduleConfigurations: false, extensions: [[$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: true, recursiveSubmodules: true, reference: '', trackingSubmodules: false],[$class: 'RelativeTargetDirectory', relativeTargetDir: repo_folder]], submoduleCfg: [], userRemoteConfigs: [[credentialsId: repo_credentials, url: repo_url]]]
    dir (repo_folder) {
        withCredentials([file(credentialsId: repo_file_credentials, variable: 'FILE')]) {
            // two steps since the submodules are located at submodule_path, y levels deep
            // step 1 - move to the submodule folder checkout the gitsha that just passed tests
            sh """
                  chmod 0500 $FILE &&
                  eval `ssh-agent` &&
                  ssh-add $FILE &&
                  git checkout ${repo_branch} &&
                  cd ${submodule_path} &&
                  git checkout ${gitsha}
                  """
            // step 2 - check if anything to commit. if so push updated .gitmodules file
            sh """
                  git status
                  git diff --exit-code && echo "No submodule changes to commit" ||
                      ( echo "Committing submodule changes"
                      git config user.email 'jenkins@cccnext' &&
                      git config user.name 'jenkins' &&
                      git config push.default simple &&
                      chmod 0500 $FILE &&
                      eval `ssh-agent` &&
                      ssh-add $FILE &&
                      git commit -a -m '${message}' &&
                      git status &&
                      git push )
                  """
        }
    }
}

// the present working directory should be the application folder that contains the pom.xml file
def scanProjectWithSonarqube(command) {
    try {
        sh "pwd && ls -al"
        tool name: 'sonarqube'
        def scannerHome = tool 'sonarqube'
        withSonarQubeEnv(sonarqube_env) {
            sh command + " org.sonarsource.scanner.maven:sonar-maven-plugin:3.9.1.2184:sonar"
        }
    }
    catch (Exception e) {
       logHandler.error(e.toString())
       currentBuild.result = 'FAILURE'
       error "Build Failed, exiting ..."
    }
}

def scanPRWithSonarqube(repo_slug, account_name, branch_name, command) {
    try {
        sh "pwd && ls -al"
        //tool name: 'sonarqube'
        def scannerHome = tool 'sonarqube-4.7'
        withSonarQubeEnv(sonarqube_env) {
            sh command + """ sonar:sonar --batch-mode --errors \
                    -Dsonar.bitbucket.repoSlug=${repo_slug} \
                    -Dsonar.bitbucket.accountName=${account_name} \
                    -Dsonar.bitbucket.teamName=devops_auto \
                    -Dsonar.bitbucket.apiKey=kUQWu3WgrrDaEEecA229 \
                    -Dsonar.bitbucket.branchName=${branch_name} \
                    -Dsonar.host.url=${sonarqube_url} \
                    -Dsonar.analysis.mode=issues"""
        }
    }
    catch (Exception e) {
        logHandler.error(e.toString())
        currentBuild.result = 'FAILURE'
        error "Build Failed, exiting ..."
    }
}

/**
 * Return the result of SonarQube scan
 *
 * @return String: Possible values are "ERROR", "WARN", and "OK"
 */
def scanResult() {
    timeout(time: 10, unit: 'MINUTES') { // Just in case something goes wrong, pipeline will be killed after a timeout
        def qg = waitForQualityGate()
        return qg.status
    }
}

def sonarScan(repo_slug, account_name, command) {
    channel = env?.channel ?: "#devops" 
    if (env.BRANCH_NAME =~ /PR-.*/) {
        scanPRWithSonarqube(repo_slug, account_name, env.CHANGE_BRANCH, command)
    } else if (env.BRANCH_NAME == "develop") {
        scanProjectWithSonarqube(command)
        def scan_result = scanResult()
        if (scan_result != 'OK') {
            slackSend channel: channel , color: "0DE8D4", message: "Failed Sonar Quality Gate.Please search for project: ${repo_slug} in Sonar UI: ${sonarqube_url}"
            return 'UNSTABLE'
        }
    }
    return 'SUCCESS'
}

def sonarScan(repo_slug) {
    sonarScan(repo_slug, "cccnext", "mvn")
}

def sonarScanPhp(repo_slug) {
    def pwd = pwd()
    sh "sed -i 's|/var/www|${pwd}|g' ../coverage-clover.xml || true"
    if (env.BRANCH_NAME =~ /^PR-/) {
        logHandler.warn("WARNING: PR scans are not functional pending resolution of DEVOPS-975")
        /*
        try {
            //tool name: 'sonarqube'
            def scannerHome = tool 'sonarqube-4.7'
            withSonarQubeEnv(sonarqube_env) {
                sh """${scannerHome}/bin/sonar-scanner -Dsonar.analysis.mode=issues \
                        -Dsonar.bitbucket.repoSlug=${repo_slug} \
                        -Dsonar.bitbucket.accountName=cccnext \
                        -Dsonar.bitbucket.teamName=devops_auto \
                        -Dsonar.bitbucket.apiKey=kUQWu3WgrrDaEEecA229 \
                        -Dsonar.bitbucket.branchName=${env.CHANGE_BRANCH} \
                        -Dsonar.host.url=${sonarqube_url}"""
            }
        }
        catch (Exception e) {
            logHandler.error(e.toString())
            currentBuild.result = 'FAILURE'
            error "Build Failed, exiting ..."
        }
        */
    } else if (env.BRANCH_NAME == "develop" || env.BRANCH_NAME =~ /feature/) {
        try {
            def scannerHome = tool name: 'sonarqube-4.7'
            withSonarQubeEnv(sonarqube_env) {
                sh "${scannerHome}/bin/sonar-scanner"
            }
        }
        catch (Exception e) {
            logHandler.error(e.getMessage())
            slackSend channel: "#devops" , color: "0DE8D4", message: "Exception raised running Sonar for: ${repo_slug}: " + e.getMessage()
            return "UNSTABLE"
        }
        def scan_result = scanResult()
        if (scan_result != 'OK') {
            return 'UNSTABLE'
        }
    } else {
        print "sonarScanPhp skipped due to non-match on branch pattern ${env.BRANCH_NAME}"
    }
    return 'SUCCESS'
}

def sonarScanPython(repo_slug) {
    def pwd = pwd()
    if (env.BRANCH_NAME =~ /^PR-/) {
        logHandler.warn("WARNING: PR scans are not functional pending resolution of DEVOPS-975")
        /*
        try {
            //tool name: 'sonarqube'
            def scannerHome = tool 'sonarqube-4.7'
            withSonarQubeEnv(sonarqube_env) {
                sh """${scannerHome}/bin/sonar-scanner -Dsonar.analysis.mode=issues \
                        -Dsonar.bitbucket.repoSlug=${repo_slug} \
                        -Dsonar.bitbucket.accountName=cccnext \
                        -Dsonar.bitbucket.teamName=devops_auto \
                        -Dsonar.bitbucket.apiKey=kUQWu3WgrrDaEEecA229 \
                        -Dsonar.bitbucket.branchName=${env.CHANGE_BRANCH} \
                        -Dsonar.host.url=${sonarqube_url}"""
            }
        }
        catch (Exception e) {
            logHandler.error(e.toString())
            currentBuild.result = 'FAILURE'
            error "Build Failed, exiting ..."
        }
        */
    } else if (env.BRANCH_NAME == "develop") {
        try {
            //tool name: 'sonarqube'
            def scannerHome = tool 'sonarqube-4.7'
            withSonarQubeEnv(sonarqube_env) {
                sh "${scannerHome}/bin/sonar-scanner"
            }
        }
        catch (Exception e) {
            logHandler.error(e.toString())
            currentBuild.result = 'FAILURE'
            error "Build Failed, exiting ..."
        }
        def scan_result = scanResult()
        if (scan_result != 'OK') {
            return 'UNSTABLE'
        }
    }
    return 'SUCCESS'
}

/**
* CheckMarx security scan
*
* This will be called by any project using Maven to scan code for vulnerabilities.
* Repositories that does not use Maven will have to call this step in the Jenkinsfile
* in the build stage.
*
**/
def checkMarxScan(incremental, do_scan, async, checkmarx_service) {

    logHandler.debug("global run_checkmarx_incremental: ${run_checkmarx_incremental}")
    logHandler.debug("global run_checkmarx_async: ${run_checkmarx_async}")
    logHandler.debug("global run_checkmarx_scan: ${run_checkmarx_scan}")

    logHandler.debug("checkMarxScan: incremental: ${incremental}")
    logHandler.debug("checkMarxScan: async: ${async}")
    logHandler.debug("checkMarxScan: do_scan: ${do_scan}")
    logHandler.debug("checkMarxScan: checkmarx_service: ${checkmarx_service}")

    checkmarx_service = service ?: checkmarx_service
    logHandler.debug("checkMarxScan: checkmarx_service (after default): ${checkmarx_service}")


    if (do_scan) {
        try { //note that some projects don't set env.service, so this call fails with missing property exception.
            return step([$class: 'CxScanBuilder', comment: '', credentialsId: 'eb715106-c020-46d6-91e6-439aa5f913ca', excludeFolders: 'target', excludeOpenSourceFolders: '', exclusionsSetting: 'global', failBuildOnNewResults: false, filterPattern: '''!**/_cvs/**/*, !**/.svn/**/*,   !**/.hg/**/*,   !**/.git/**/*,  !**/.bzr/**/*, !**/bin/**/*,
!**/obj/**/*,  !**/backup/**/*, !**/.idea/**/*, !**/*.DS_Store, !**/*.ipr,     !**/*.iws,
!**/*.bak,     !**/*.tmp,       !**/*.aac,      !**/*.aif,      !**/*.iff,     !**/*.m3u, !**/*.mid, !**/*.mp3,
!**/*.mpa,     !**/*.ra,        !**/*.wav,      !**/*.wma,      !**/*.3g2,     !**/*.3gp, !**/*.asf, !**/*.asx,
!**/*.avi,     !**/*.flv,       !**/*.mov,      !**/*.mp4,      !**/*.mpg,     !**/*.rm,  !**/*.swf, !**/*.vob,
!**/*.wmv,     !**/*.bmp,       !**/*.gif,      !**/*.jpg,      !**/*.png,     !**/*.psd, !**/*.tif, !**/*.swf,
!**/*.jar,     !**/*.zip,       !**/*.rar,      !**/*.exe,      !**/*.dll,     !**/*.pdb, !**/*.7z,  !**/*.gz,
!**/*.tar.gz,  !**/*.tar,       !**/*.gz,       !**/*.ahtm,     !**/*.ahtml,   !**/*.fhtml, !**/*.hdm,
!**/*.hdml,    !**/*.hsql,      !**/*.ht,       !**/*.hta,      !**/*.htc,     !**/*.htd, !**/*.war, !**/*.ear,
!**/*.htmls,   !**/*.ihtml,     !**/*.mht,      !**/*.mhtm,     !**/*.mhtml,   !**/*.ssi, !**/*.stm,
!**/*.stml,    !**/*.ttml,      !**/*.txn,      !**/*.xhtm,     !**/*.xhtml,   !**/*.class, !**/*.iml, !Checkmarx/Reports/*.*, !**/*.xml, !**/*.yml, !**/*@tmp/**/*
    ''', fullScanCycle: 10, incremental: incremental, groupId: '22222222-2222-448d-b029-989c9070eb23', includeOpenSourceFolders: '', osaArchiveIncludePatterns: '*.zip, *.war, *.ear, *.tgz', osaInstallBeforeScan: false, preset: '36', projectName: checkmarx_service, sastEnabled: true, serverUrl: 'https://checkmarx.cccsecuritycenter.org', sourceEncoding: '1', username: ''])
        //FIXME: add async scan option.  It likely needs to just use cli, not plugin
        } catch (e) {
            logHandler.error("Error calling checkmarx scan: " + e)
            return false
        }
    }
    return true
}

def abortOldBuilds() {
    def hi = Hudson.instance
    def items = env.JOB_NAME.split('/')
    def branch
    branch = hi.getItem(items[0])
    // Move down the path, usually pipeline/branch or folder/pipeline/branch,
    // getting each item from its parent until we reach the branch.
    items[1..-1].each{ item ->
        branch = branch.getItem(item)
    }

    branch.getBuilds().each{ build ->
        def exec = build.getExecutor()

        if (build.number != currentBuild.number && exec != null) {
            build.result = Result.ABORTED
            exec.interrupt()
            logHandler.info("Aborted previous running build #${build.number}")
        }
    }
}

def copyScript(scriptName) {
  if (fileExists(scriptName)) {
    logHandler.info("${scriptName} exists: not overwriting")
    return false
  } else {
    logHandler.info("copying scripts/${scriptName} to workspace")
    scriptContent = libraryResource "scripts/${scriptName}"
    writeFile file: "${scriptName}", text: scriptContent
    sh "chmod +x ${scriptName}"
    return true
  }
}

def removeCopiedScript(scriptName) {
    logHandler.info("removing copied script, ${scriptName}")
    sh "rm ${scriptName}"
}

/**
* Scan images with the clair scanner
* Vulnerabilities to be ignored can be added on a per-image, or general basis to the whitelist
*
* @param image - the image + tag to scan (format image:tag)
*              - either a local or registry-based image may be used here
* @param level - one of Low, Medium, High, Critical.  The level of vulnerability reporting to use
* @param channel - the notification channel to send non-passing results to.
* @param ignore_failure - true || false. whether to set build to unstable on failure or ignore
**/

def imageScan(Map config) {
    //NOTE: whitelist file showing example format - these CVE's are commented out
    writeFile file: "${WORKSPACE}/whitelist.yml", text: '''
general:
     #- CVE-2018-0500
images:
   ccctechcenter/coci2-front:
     #- CVE-2018-0500
     #- CVE-2018-14618
   ccctechcenter/conductor-server:
     # Note we're actually using a patched version of jq for this vulnerability, but the package maintainer hasn't bumped the version to satisfy the scanner. Ref: https://github.com/stedolan/jq/issues/1406
     - CVE-2016-4074
'''
    config.channel = config?.channel ?: "#cve-scan-alerts"
    config.level = config?.level ?: "High"
    config.ignore_failure = config?.ignore_failure != null ? config.ignore_failure : true
    logHandler.debug("image to scan: ${config.image}")
    logHandler.debug("clair scan level: ${config.level}")
    logHandler.debug("ignore failure during scan: ${config.ignore_failure}")
    logHandler.debug("scan result reporting to channel: ${config.channel}")
    count = 0
    klar_status = 2
    while(count < 2 && klar_status == 2){
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: '00733feb-a287-4875-94ba-e6b5c16c56a8', usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD']]) {
            klar_status = sh returnStatus: true, script: "docker run -v ${WORKSPACE}/whitelist.yml:/go/klar/whitelist.yml:ro --env WHITELIST_FILE=/go/klar/whitelist.yml \
                --env CLAIR_OUTPUT=${config.level} --env DOCKER_USER=${REGISTRY_USERNAME} \
                --env DOCKER_PASSWORD=${REGISTRY_PASSWORD} --env CLAIR_ADDR=https://clair_devops:clair_devops@clair.ccctechcenter.org \
                chessracer/klar ${registry_url}/${config.image} > klar_output"
        }
        count += 1
    }
    output = readFile 'klar_output'
    logHandler.debug("Clair image scan output: $output")
    logHandler.debug("klar_status: $klar_status")
    archiveArtifacts 'klar_output'
    if (klar_status == 1) {
        logHandler.warn("Level High or greater failures found during docker image scan: Clair image scan output: $output")
        logHandler.warn("klar_status: $klar_status")
        slackSend channel: config.channel , color: "#ffb3d9", message: "${config.image} Failed Clair Docker Image CVE scan.  Found vulnerabilities rated ${config.level} or greater: ${output}.\nTo resolve this alert, either fix the vulnerabilities or add them to the whitelist (pending approval from CSO). \nReference: https://cccnext.jira.com/wiki/spaces/DEVOPS/pages/728007635/Docker+Image+Vulnerability+Scans+with+Klar. \nJob:  ${env.BUILD_URL}"
        if (config.channel != "#cve-scan-alerts") { //also send to scan alerts channel 
            slackSend channel: '#cve-scan-alerts' , color: "#ffb3d9", message: "${config.image} Failed Clair Docker Image CVE scan.  Found vulnerabilities rated ${config.level} or greater: ${output}.\nTo resolve this alert, either fix the vulnerabilities or add them to the whitelist (pending approval from CSO). \nReference: https://cccnext.jira.com/wiki/spaces/DEVOPS/pages/728007635/Docker+Image+Vulnerability+Scans+with+Klar. \nJob:  ${env.BUILD_URL}"
        }
        if (!config.ignore_failure) {
            currentBuild.result = 'UNSTABLE'
        }
    }
    if (klar_status == 2) {
        slackSend channel: config.channel , color: "#ffb3d9", message: "${config.image} Clair CVE scan didn’t run.   \nJob:  ${env.BUILD_URL}"
        if (config.channel != "#cve-scan-alerts") { //also send to scan alerts channel 
            slackSend channel: '#cve-scan-alerts' , color: "#ffb3d9", message: "${config.image} Clair CVE scan didn’t run.  \nJob:  ${env.BUILD_URL}"
        }
        if (!config.ignore_failure) {
            currentBuild.result = 'UNSTABLE'
        }
    }
}
