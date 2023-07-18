
//deploy functions
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.transform.Field
import java.text.SimpleDateFormat
import java.util.*
import java.time.*
import java.sql.*
import groovy.time.TimeCategory

//global variables
@Field domain = "ccctechcenter.org"
@Field rancher_url = "https://rancher-server.${domain}"
@Field registry_url = "registry.${domain}:5000"
@Field LOG_LEVEL = env.LOG_LEVEL
@Field container_log = true //switch to enable container log if first container processed
@Field log_length = env.Log_Lines > 0 ? env.Log_Lines : 100 //use env variable if a JF has a setting for Log_lines, otherwise use the default value of 100

def setupDeployEnv() {
    def rancherCompose = tool 'rancher-compose-v12.5' // gives us rancher-compose for deploy operations
    env.PATH = "${rancherCompose}:${env.PATH}"
    def rancherCli = tool 'rancher-cli-v0.6.12' // gives us "rancher" for cli operations against the server (eg: pull logs, describe envs, etc)
    env.PATH = "${rancherCli}:${env.PATH}"
}

def setRancherUrl(environment, service) {
    setRancherUrl(environment, service, "ccctechcenter.org")
}

def setRancherUrl(environment, service, domain) {
    //TODO: cccnext-college-adaptor + college-adaptor-deploy, and apollo-client (deployed to adaptor stacks) will need special handling not accounted for in the logic here. Depending on the "stack" (aka rancher_env), it will target a different rancher host: college-adaptor-test stack (aka "College Adaptor Integration") would logically go to the .dev instance, regardless of what "environment" it's deployed to.  Alternative, is to just ignore that edge case and keep it in the primary rancher instance.

    //DEVOPS-767 - Use the folowing logic to handle college-adaptor exception
    if (service =~ /college-adaptor/ ||
        service =~ /apollo-client/ ||
        service =~ /logstash/ ||
        service =~ /kibana/ ||
        environment == "pilot" ||
        environment == "prod") {
        rancher_url = "https://rancher-server.${domain}"
    } else {
        rancher_url = "https://rancher.dev.${domain}"
    }
    if (domain != "ccctechcenter.org") { //support for arbitrary domains
        rancher_url = "https://rancher.${domain}"
        registry_url = "registry.${domain}:5000"
    }
    return rancher_url
}

// @Deprecated - the paramaterized method signatures for runDeploy have been deprecated in favor of the runDeploy(Map config) signature.
def runDeploy(service, environment, tag, url, port, protocol, health, rancher_key, rancher_pass, channel){//10-arg
  runDeploy service: service, environment: environment, tag: tag, url: url, port: port, protocol: protocol, health: health, rancher_key: rancher_key, rancher_pass: rancher_pass, channel: channel
}

def runDeploy(stack, service, environment, tag, url, port, protocol, health, rancher_key, rancher_pass, channel){//11-arg
  runDeploy stack: stack, service: service, environment: environment, tag: tag, url: url, port: port, protocol: protocol, health: health, rancher_key: rancher_key, rancher_pass: rancher_pass, channel: channel
}

def runDeployForOrg(stack, service, environment, tag, url, port, protocol, health, rancher_key, rancher_pass, channel, org) {//12-arg
  runDeploy stack: stack, service: service, environment: environment, tag: tag, url: url, port: port, protocol: protocol, health: health, rancher_key: rancher_key, rancher_pass: rancher_pass, channel: channel, org: org
}

def runDeploy(rollback_on_failure, stack, service, environment, tag, url, port, protocol, health, rancher_key, rancher_pass, channel) {//12-arg
  runDeploy rollback_on_failure: rollback_on_failure, stack: stack, service: service, environment: environment, tag: tag, url: url, port: port, protocol: protocol, health: health, rancher_key: rancher_key, rancher_pass: rancher_pass, channel: channel
}

def runDeploy(rollback_on_failure, stack, service, environment, tag, url, port, protocol, health, rancher_key, rancher_pass, channel, org) {//13-arg
  runDeploy rollback_on_failure: rollback_on_failure, stack: stack, service: service, environment: environment, tag: tag, url: url, port: port, protocol: protocol, health: health, rancher_key: rancher_key, rancher_pass: rancher_pass, channel: channel, org: org
}

def runDeploy(rollback_on_failure, stack, service, environment, tag, url, port, protocol, health, rancher_key, rancher_pass, channel, org, state) {//14-arg
  runDeploy rollback_on_failure: rollback_on_failure, stack: stack, service: service, environment: environment, tag: tag, url: url, port: port, protocol: protocol, health: health, rancher_key: rancher_key, rancher_pass: rancher_pass, channel: channel, org: org, state: state
}

def runDeploy(Map config) {

    config = setConfigDefaults(config)

    setRancherUrl(config.environment, config.service, config.domain)
    logHandler.debug("rancher_url: ${rancher_url}")

    health_result = true

    if ((config.validate_tag.toString() == "true") && !validateTag(config.service, config.tag, config.org)) {
        throw new Exception("private registry for service $config.service does not contain the specified tag $config.tag")
    }

    try {
        // beginPauseWindow is hours from today midnight
        // endPauseWindow is hours from midnight to end ; must be greater than beginPauseWindow
        downtimeStart = 22 //10pm
        downtimeEnd = 5 //5am
        beginPauseWindow = downtimeStart
        endPauseWindow = 24 + downtimeEnd
        if ( (config.environment == "ci" || config.environment == "qa") && !config.scheduled.toBoolean() ) {
            pauseDeploy(beginPauseWindow, endPauseWindow, config.channel, config.environment, config.service, config.tag, config.stack)
        }
    } catch (err) {
        logHandler.error("Timeout expired.. Proceeding with Next Steps : " + err)
    }

    config.rancher_url = rancher_url
    //verifyState(rancher_url, config.rancher_key, config.rancher_pass, config.stack, config.service, config.environment)
    verifyState(config)

    //deploy_result = deployService stack: config.stack, service: config.service, environment: config.environment, tag: config.tag, rancher_key: config.rancher_key, rancher_pass: config.rancher_pass, interval: config.interval, state: config.state, compose_file_prefix: config.compose_file_prefix, batch_size: config.batch_size, timeout: config.timeout
    deploy_result = deployService(config)
    if (!deploy_result) {
        health_result = false
        logHandler.error("Deploy or upgrade failed")
        logHandler.debug("Rolling back")

        slackSend channel: config.channel, color: "0DE8D4", message: "${config.environment} deploy of ${config.service}:${config.tag} in ${config.stack} experienced an error for branch ${env.BRANCH_NAME}. Service rollback will be initiated. Check the status of the service in Rancher UI to validate. \nJob: ${env.BUILD_URL}"
        //cross-post to the devops config.channel - often some action is needed in Rancher in these instances.
        slackSend channel: "#devops", color: "0DE8D4", message: "${config.environment} deploy of ${config.service}:${config.tag} in ${config.stack} experienced an error for branch ${env.BRANCH_NAME}. Service rollback will be initialted. Check the status of the service in Rancher UI to validate. \nJob: ${env.BUILD_URL}"
    } else {
        health_result = config.health ? testService(config.service, config.url, config.health, config.port, config.protocol, config.health_status) : true
    }

    if (health_result) {
        getContainerLogFromStack(log_length, config.service, config.stack, config.rancher_key, config.rancher_pass)
        if (!confirmUpgradeService(config)) {
            logHandler.error("confirm-upgrade of application $config.service failed, rolling back")
            rancherApiCall(config.service, config.rancher_key, config.rancher_pass, "cancelupgrade", config.stack) // cancel-upgrade not supported via the cli
            sleep 10 // to let the cancelupgrade a chance to work
            rollbackService(config)
            throw new Exception("confirm-upgrade failed for service: $config.service")
        }
    } else {
        getContainerLogFromStack(log_length, config.service, config.stack, config.rancher_key, config.rancher_pass)
        if (config.rollback_on_failure.toString() == "true") {
            logHandler.error("Deployment of application $config.service failed, rolling back")
            rollbackService(config)
            throw new Exception("Deployment rolled back for service: $config.service")
        } else {
            logHandler.error("Deployment of application $config.service failed, but user chose not to rollback")
            throw new Exception("Deployment failed for service: $config.service ")
        }
    }
    if (config.environment == "prod") {
        try {
            if (NEWRELIC_BLACKLIST.any { config.service =~ /$it/ } ) {
                logHandler.info("${config.service} is blacklisted for deploy recording in NewRelic, skipping... ")
                return
            }
            app_name = ""
            names = [config.service + "-" + config.environment, config.stack + "-" + config.environment, config.stack]
            signalFxAPIToken = ceEnv.getSingleSSMParameter('SignalFxAPIToken', 'qa')
            for (name in names) {
                // Since we're piping into jq, it will always return 0, but return
                // "null" to stdout if there are no results
                app_count = sh(returnStdout: true, script: """curl -X GET --header "X-SF-TOKEN: $signalFxAPIToken" https://api.us1.signalfx.com/v2/dimension?query=service:${name} | jq -r '.count'""").trim()
                logHandler.debug("app_count: ${app_count}")
                if (app_count == "1") {
                    app_name = name
                    break
                }
            }
            if (app_name != "") {
                recordDeployInSFX(app_name, config.tag, config.changelog)
            } else {
                logHandler.warn("Could not record deploy $config.tag for $config.service in SignalFX")
                slackSend channel: "#devops", color: "warning", \
                    message: "Could not record deploy $config.tag for $config.service in SignalFX\nJob: ${env.BUILD_URL}"
            }
        } catch (Exception e) {
            logHandler.warn("Could not record deploy info in SignalFX\n" + e.toString())
        }
    }
}

/* Takes in standard dates, startTime in the future and endTime after that; computes timeoutTime in minutes
 * Slacks pause information
 */
def pauseDeploy(int HoursFromMidnightStart, int HoursFromMidnightEnd, String channel, String environment, String service, String tag, String stack){
    setupDeployEnv()

    LocalTime midnight = LocalTime.MIDNIGHT
    LocalDate dateOnly = LocalDate.now()
    LocalDateTime nowMid =  LocalDateTime.of(dateOnly,midnight) //setup and get today at midnight

    //evening pause window
    TodayDowntimeStart = nowMid.plusHours(HoursFromMidnightStart)
    TSTodayDowntimeStart  = Timestamp.valueOf(TodayDowntimeStart)
    Date DateTodayDowntimeStart = new Date(TSTodayDowntimeStart.getTime() )

    TomorrowDowntimeEnd = nowMid.plusHours(HoursFromMidnightEnd)
    TSTomorrowDowntimeEnd  = Timestamp.valueOf(TomorrowDowntimeEnd)
    Date DateTomorrowDowntimeEnd = new Date(TSTomorrowDowntimeEnd.getTime() )

    //morning pause window
    YesterdayDowntimeStart = nowMid.minusHours(24 % HoursFromMidnightStart)
    TSYesterdayDowntimeStart  = Timestamp.valueOf(YesterdayDowntimeStart)
    Date DateYesterdayDowntimeStart = new Date(TSYesterdayDowntimeStart.getTime() )

    TodayDowntimeEnd = nowMid.plusHours(HoursFromMidnightEnd % 24)
    TSTodayDowntimeEnd  = Timestamp.valueOf(TodayDowntimeEnd)
    Date DateTodayDowntimeEnd = new Date(TSTodayDowntimeEnd.getTime() )

    Date rightNow = new Date() //get ready for logic gate below

    if ( rightNow.after(DateTodayDowntimeStart) || (rightNow.after(DateTodayDowntimeEnd) && rightNow.before(DateTodayDowntimeStart)) ) {
        logHandler.info("Evening: pause window times: " + DateTodayDowntimeStart.getDateTimeString() + " and " + DateTomorrowDowntimeEnd.getDateTimeString())
        beginPauseWindow = TodayDowntimeStart
        endPauseWindow = TomorrowDowntimeEnd
    } else if (rightNow.before(DateTodayDowntimeEnd) ) {
        logHandler.info("Morning: pause window times: " + DateYesterdayDowntimeStart.getDateTimeString() + " and " + DateTodayDowntimeEnd.getDateTimeString())
        beginPauseWindow = YesterdayDowntimeStart
        endPauseWindow = TodayDowntimeEnd
    } else {
        logHandler.info("No Pause Window found (we should not get here). \nDateTodayDowntimeStart: " + DateTodayDowntimeStart.getDateTimeString() + "\nDateTomorrowDowntimeEnd: " + DateTomorrowDowntimeEnd.getDateTimeString() + "\nDateYesterdayDowntimeStart: " + DateYesterdayDowntimeStart.getDateTimeString() + "\nDateTodayDowntimeEnd: " + DateTodayDowntimeEnd.getDateTimeString())
    }

    //convert back to Date before call
    TSbeginPauseWindow = Timestamp.valueOf(beginPauseWindow)
    TSendPauseWindow = Timestamp.valueOf(endPauseWindow)
    Date DatebeginPauseWindow = new Date(TSbeginPauseWindow.getTime() )
    Date DateendPauseWindow = new Date(TSendPauseWindow.getTime() )

    Date now = new Date()
    startBeforeNow = now.after(DatebeginPauseWindow)
    endAfterNow = now.before(DateendPauseWindow)
    // is function beiing called during downtime window?
    if ( startBeforeNow && endAfterNow ) { //if being called during downtime windown then pause
        logHandler.info("pauseDeploy was called during downtime window of " +DatebeginPauseWindow.getDateTimeString()+" and " +DateendPauseWindow.getDateTimeString())
        //pauseTime will be the length of time between time invoked and end of pause window
        pauseTime = DateendPauseWindow.getTime() - now.getTime() //diff in millisec
        timeoutTime = pauseTime/(1000*60)  //convert to sec, then minutes
        IntTimeoutTime = (int)timeoutTime
        stringStartTime = DatebeginPauseWindow.getDateTimeString() //for string interpolation in message
        stringEndTime = DateendPauseWindow.getDateTimeString()
        timeout(time: "$IntTimeoutTime", unit: "MINUTES") {
        slackSend channel: channel, color: "0DE8D4", message: "${environment} deploy of ${service}:${tag} in ${stack} paused for branch ${env.BRANCH_NAME}. The deploy will be resumed after scheduled downtime window ${stringStartTime} to ${stringEndTime}. To un-pause deploy now (WARNING: this will likely cause your job to fail, as resources may not be available), choose “approve” at below \nJob: ${env.BUILD_URL}input/"
            input message: "Deployment Paused due to scheduled downtime from $stringStartTime - $stringEndTime. It will be resumed after $stringEndTime, ok: 'No user action required'"
        }
    } else {
        logHandler.info("pauseDeploy was not called during downtime window of " +DatebeginPauseWindow.getDateTimeString()+" and " +DateendPauseWindow.getDateTimeString())
    }
}

/*
   checks if the current state is updating active and removes the service for that state.

   @param stack  the name of the rancher stack you want to query
   @param service the name of the service you want to query
   @param url address of the rancher server
   @param key rancher access key
   @param secret rancher secret key
   @param environment rancher environment
*/
def verifyState(url, key, secret, stack, service, environment) {
    config.rancher_url = url
    config.rancher_key = key
    config.rancher_pass = secret
    config.stack = stack
    config.service = service
    config.environment = environment
    verifyState(config)
}

def verifyState(config) {
    config.version = config.version ?: ""
    setupDeployEnv()
    state = "inactive"
    state = sh returnStdout: true, script: "#!/bin/sh -e\nrancher --url ${config.rancher_url} --access-key ${config.rancher_key} --secret-key ${config.rancher_pass} inspect --format {{.state}} ${config.stack}/${config.service}"
    state = state.trim()
    logHandler.debug("verifyState: state: ${state}")
    if (state == "updating-active" && config.version == "" ) { //if version is set we're deploying from catalog, and cannot remove service regardless of current state
       slackSend channel: "#devops", color: "0DE8D4",message: "WARN: State of service ${config.service} is $state, so removing the service prior to attempting upgrade.\nJenkins Job: ${env.BUILD_URL}"
       removeService(config.stack, config.service, config.environment, config.rancher_key, config.rancher_pass)
    }
}
//helpers:

/*
   blocks and waits until state == desired_state. timeouts expected to be handled by caller.

   @param stack  the name of the rancher stack you want to query
   @param service the name of the service you want to query
   @param desired_state the state of the service we're waiting to achieve. Typically "upgraded" or "active"
   @param url address of the rancher server
   @param key rancher access key
   @param secret rancher secret key
*/
def waitForServiceState(url, key, secret, stack, service, desired_state) {
    setupDeployEnv() //make sure we have rancher cli
    def state
    while ({
        state = null
        if (!stack) {
            state = sh returnStdout: true, script: "#!/bin/sh -e\nrancher --url ${url} --access-key ${key} --secret-key ${secret} inspect --format {{.state}} ${service}"
        } else {
            state = sh returnStdout: true, script: "#!/bin/sh -e\nrancher --url ${url} --access-key ${key} --secret-key ${secret} inspect --format {{.state}} ${stack}/${service}"
        }
        state = state.trim()
        logHandler.debug("waitForServiceState for ${service} state: ${state} desired_state: ${desired_state}")
        sleep 5
        if (state =~ /error/) { //workers aren't accessible by same stack-name/service-name convention.. just skip out on validation for now.
            false
        } else {
            state != "${desired_state}"
        }
    }()) continue
    if (state =~ /error/) { //workers aren't accessible by same stack-name/service-name convention.. just skip out on validation for now.
        logHandler.warn("waitForServiceState: error state detected, skipping validation for ${stack}/${service}")
        return true
    } else {
        return state == "${desired_state}"
    }
}

def deployService(service, environment, tag, rancher_key, rancher_pass) {
    return deployService("course-exchange-${environment}", service, environment, tag, rancher_key, rancher_pass)
}

def deployService(stack, service, environment, tag, rancher_key, rancher_pass) {
  if(environment == "test" || environment == "pilot" || environment == "prod") {
    return deployService(stack, service, environment, tag, rancher_key, rancher_pass,"180000")
  }
  else {
    return deployService(stack, service, environment, tag, rancher_key, rancher_pass,"60000")
  }
}

def deployService(stack, service, environment, tag, rancher_key, rancher_pass, interval) {
    return deployService(stack, service, environment, tag, rancher_key, rancher_pass, interval, "upgraded")
}

def deployService(stack, service, environment, tag, rancher_key, rancher_pass, interval, state) {
    return deployService(stack, service, environment, tag, rancher_key, rancher_pass, interval, state, "docker-compose")
}

def deployService(stack, service, environment, tag, rancher_key, rancher_pass, interval, state, compose_file_prefix) {
    deployService stack: stack, service: service, environment: environment, tag: tag, rancher_key: rancher_key, rancher_pass: rancher_pass, interval: interval, state: state, compose_file_prefix: compose_file_prefix, batch_size: "1"
}

def setConfigDefaults(Map config) {

    //checks for nulls and set defaults
    config.service = config?.service ?: ""
    config.environment = config?.environment ?: "ci"
    config.rancher_tag = config?.rancher_tag ?: ""
    config.rancher_pass = config?.rancher_pass ?: ""
    config.stack = config?.stack ?: "course-exchange-${config.environment}"
    config.rollback_on_failure = config.rollback_on_failure != null ? config.rollback_on_failure : true
    config.org = config?.org ?: "ccctechcenter"
    config.state = config?.state ?: "upgraded"
    config.url = config?.url ?: ""
    config.port = config?.port ?: ""
    config.protocol = config?.protocol ?: ""
    config.health = config?.health ?: ""
    config.changelog = config?.changelog ?: ""
    config.channel = config?.channel ?: "#devops"
    config.validate_tag = config.validate_tag != null ? config.validate_tag : true
    config.health_status = config?.health_status ?: "UP"
    config.compose_file_prefix = config?.compose_file_prefix ?: "docker-compose"
    config.batch_size = config?.batch_size ?: "1"
    config.domain = config?.domain ?: domain
    config.timeout = config?.timeout ?: "10"
    config.tag = config?.tag ? config.tag.trim() : "latest"
    config.scheduled = config?.scheduled ?: false
    //new to support catalog in-place upgrades:
    config.version = config?.version ?: ""
    config.directory = config?.directory ?: ""
    config.answers = config?.answers ?: ""

    if(config.environment == "test" || config.environment == "qa" || config.environment == "pilot" || config.environment == "prod") {
        config.interval = config?.interval ?: "30000" //still honor interval if it was set by the caller
    }
    else {
        config.interval = config?.interval ?: "15000"
    }

    //Some other config we want to try and set defaults for

    if (config.service =~ /logspout/ || config.service =~ /logio/) {
        config.batch_size = "10" //large batch size due to global nature of containers
    }

    //set defaults for these so rancher doesn't spam about them not being set
    def SPRINGFRAMEWORK_LOG_LEVEL
    def logspout_endpoint
    def ENCRYPT_KEY
    def FLYWAY_CMD

    SPRINGFRAMEWORK_LOG_LEVEL = env.SPRINGFRAMEWORK_LOG_LEVEL ?: "INFO"
    logspout_endpoint = env.logspout_endpoint ?: null
    ENCRYPT_KEY = env.ENCRYPT_KEY ?: null
    FLYWAY_CMD = env.FLYWAY_CMD ?: null

    config.extra_env_vars = "IMAGE_TAG=$config.tag spring_profile=$config.environment SPRINGFRAMEWORK_LOG_LEVEL=$SPRINGFRAMEWORK_LOG_LEVEL ENCRYPT_KEY=$ENCRYPT_KEY FLYWAY_CMD=$FLYWAY_CMD logspout_endpoint=$logspout_endpoint"

    config.each { key, value ->
        if (key =~ /pass/) {
            logHandler.debug("config.${key} *******")
        } else {
            logHandler.debug("config.${key} ${value}")
        }
    }

    return config
}

def deployService(Map config) {

    config = setConfigDefaults(config)

    try {
        timeout(time:config.timeout, unit:'MINUTES') {
            setupDeployEnv()
            logHandler.info("Deploying ${config.service} with an interval of ${config.interval}ms and batch_size: ${config.batch_size}")
            if (config.version != "") { //if version is set, we need to do an in-place upgrade using catalog templates
                logHandler.debug("Catalog version ${config.version} has been set, performing in-place upgrade using catalog")
                shNoEcho("$config.extra_env_vars rancher --url $rancher_url --access-key $config.rancher_key --secret-key $config.rancher_pass up -f ${config.directory}/docker-compose.yml --stack ${config.stack} --rancher-file ${config.directory}/rancher-compose.yml --env-file ${config.answers} -d --batch-size '${config.batch_size}' --interval '${config.interval}' --force-upgrade --pull ${config.service}")
            } else {
                logHandler.debug("No catalog version specified, performing traditional service upgrade")
                shNoEcho("$config.extra_env_vars rancher --url $rancher_url --access-key $config.rancher_key --secret-key $config.rancher_pass up -f ${config.compose_file_prefix}-${config.environment}.yml -s ${config.stack} --rancher-file rancher-compose-${config.environment}.yml -d --batch-size '${config.batch_size}' --interval '${config.interval}' --force-upgrade --pull ${config.service}")
            }
            waitForServiceState(rancher_url, config.rancher_key, config.rancher_pass, config.stack, config.service, config.state)
        }
    } catch (Exception e) {
        logHandler.error("inside catch for deployService: caught error on upgrade: " + e.toString())
        return false
    }

    //insure service is at desired scale
    if (config.version != "") {
        //FIXME: add support for re-scaling service if doing a catalog based update
        logHandler.debug("Dynamic service scaling not yet supported with rancher-catalog based deploys")
    } else {
        def rancher_yml = readYaml file: "rancher-compose-${config.environment}.yml"
        if (rancher_yml."${config.service}" != null) {
            def desired_scale = rancher_yml."${config.service}".scale
            def current_scale = getServiceScale(config.stack, config.service, config.rancher_key, config.rancher_pass)
            logHandler.debug("${config.service} current_scale: ${current_scale} desired_scale: ${desired_scale}")
            if (current_scale && desired_scale && (desired_scale.toString() != current_scale.toString())) {
                return setServiceScale(desired_scale, config.stack, config.service, config.rancher_key, config.rancher_pass)
            }
        } else {
            logHandler.debug("${config.service} not defined in rancher-compose, not attempting to scale it")
        }
    }
    return true
}


def removeService(stack, service, environment, rancher_key, rancher_pass) {
    setupDeployEnv()
    timeout(time:10, unit:'MINUTES') {
      shNoEcho("rancher --url $rancher_url --access-key $rancher_key --secret-key $rancher_pass rm -s ${stack}/$service")
    }
}


def stopService(stack, service, environment, rancher_key, rancher_pass) {
    setupDeployEnv()
    timeout(time:5, unit:'MINUTES') {
      shNoEcho("rancher --url $rancher_url --access-key $rancher_key --secret-key $rancher_pass stop ${stack}/$service")
    }
}


def testService(service, url, health, port, protocol, health_status) {
    logHandler.debug("service: $service")
    logHandler.debug("port: $port")
    logHandler.debug("protocol $protocol")
    logHandler.debug("url: $url")
    logHandler.debug("health: $health")
    def endpoint

    if (port != "" && !(url =~ /:\d+/)) {
        url = url + ":" + port
    }
    if (url =~ /http/) { //url already had a protocol on it
       endpoint = url + health
    } else {
       endpoint = protocol + "://" + url + health
    }

    logHandler.debug("endpoint: $endpoint")
    try {
        if (health =~ /health/) { //if we're really testing a health endpoint:
            assert checkAppHealth(endpoint,20,15,health_status) : "$endpoint is not accessible"
        } else { //just validate we get a 200 response
            assert checkAppStatus(endpoint,20,15) : "$endpoint is not accessible"
        }
        //retry(10) {
        //sh "sleep 30"
        //validateInfo(url, ci_port.get(service, 80), ci_protocol.get(service, "https"), service, tag)
        //echo "DEBUG: info validated for $service"
        //}
    } catch (AssertionError e) {
        println e.toString()
        return false
    }
    return true
}

def rollbackService(service, environment, tag, rancher_key, rancher_pass) {
    return rollbackService("course-exchange-${environment}", service, environment, tag, rancher_key, rancher_pass)
}

def rollbackService(stack, service, environment, tag, rancher_key, rancher_pass) {
    return rollbackService(stack, service, environment, tag, rancher_key, rancher_pass, "docker-compose")
}
def rollbackService(stack, service, environment, tag, rancher_key, rancher_pass, compose_file_prefix) {
    rollbackService stack: stack, service: service, environment: environment, tag: tag, rancher_key: rancher_key, rancher_pass: rancher_pass, compose_file_prefix: compose_file_prefix
}

def rollbackService(Map config) {

    config = setConfigDefaults(config)

    setupDeployEnv()
    timeout(time:10, unit:'MINUTES') {
        if (config.version != "") { //if version is set, we need to do an in-place upgrade using catalog templates
            logHandler.debug("Catalog version ${config.version} has been set, performing rollback using catalog")
            shNoEcho("$config.extra_env_vars spring_profile=$config.environment rancher --url $config.rancher_url --access-key $config.rancher_key --secret-key $config.rancher_pass up -f ${config.directory}/docker-compose.yml -s ${config.stack} --rancher-file ${config.directory}/rancher-compose.yml --env-file ${config.answers} -d --rollback ${config.service}")
        } else {
            shNoEcho("$config.extra_env_vars spring_profile=$config.environment rancher --url $config.rancher_url --access-key $config.rancher_key --secret-key $config.rancher_pass up -f ${config.compose_file_prefix}-${config.environment}.yml -s ${config.stack} --rancher-file rancher-compose-${config.environment}.yml -d --rollback ${config.service}")
        }
        waitForServiceState(config.rancher_url, config.rancher_key, config.rancher_pass, config.stack, config.service, "active")
    }
}


def confirmUpgradeService(service, environment, tag, rancher_key, rancher_pass) {
    return confirmUpgradeService(service: service, environment: environment, tag: tag, rancher_key: rancher_key, rancher_pass: rancher_pass)
}

def confirmUpgradeService(Map config) {
    config = setConfigDefaults(config)

    setupDeployEnv()
    try {
        timeout(time:10, unit:'MINUTES') {
        //NOTE: this also confirms any prior pending upgrades, but ignore error if this has already been done. Will only occur if prior upgrade failed, and had not been cleared
            if (config.version != "") { //if version is set, we need to do an in-place upgrade using catalog templates
                logHandler.debug("Catalog version ${config.version} has been set, performing confirm-upgrade using catalog")
                shNoEcho("$config.extra_env_vars rancher --url $rancher_url --access-key $config.rancher_key --secret-key $config.rancher_pass up -f ${config.directory}/docker-compose.yml --stack ${config.stack} --rancher-file ${config.directory}/rancher-compose.yml --env-file ${config.answers} -d --confirm-upgrade ${config.service}")
            } else {
                shNoEcho("$config.extra_env_vars rancher --url $rancher_url --access-key ${config.rancher_key} --secret-key ${config.rancher_pass} up -f ${config.compose_file_prefix}-${config.environment}.yml -s ${config.stack} --rancher-file rancher-compose-${config.environment}.yml -d --confirm-upgrade ${config.service}")
            }
            waitForServiceState(rancher_url, config.rancher_key, config.rancher_pass, config.stack, config.service, "active")
        }
    } catch (Exception e) {
        logHandler.error("ERROR: Error during confirm-upgrade error: " + e.toString())
        slackSend channel: "${config.channel}", color: "0DE8D4", message: "${config.environment} deploy of ${config.service}:${config.tag} in ${config.stack} experienced client.Timeout error for branch ${env.BRANCH_NAME} during confirm-upgrade. Check the status of the upgrade in Rancher to insure it completed \nJob: ${env.BUILD_URL}"
        return false
    }
    return true
}

def stopContainer(containerName) {
  try {
    sh "docker inspect --format='{{ .State.Running }}' " + containerName + " 2> /dev/null > .status"
  } catch (hudson.AbortException ae) {  //exception if no container exists yet.
    logHandler.debug("No running container found for name ${containerName}, doing nothing")
    return true
  }
  def status = readFile '.status'
  logHandler.debug("${containerName} status (running true/false): " + status.toBoolean())
  if (status.toBoolean() == true) {
      try {
        logHandler.debug("stopping container $containerName")
        sh "docker stop $containerName"
      } catch (all) {
        logHandler.debug("error stopping ${containerName}")
        return false
      }
  }
  return true
}

def startContainer(containerName) {
  try {
    sh "docker inspect --format='{{ .State.Running }}' " + containerName + " 2> /dev/null > .status"
  } catch (hudson.AbortException ae) {
    logHandler.debug("no stopped container found by the name: ${containerName}")
    return false
  }
  def status = readFile '.status'
  logHandler.debug("${containerName} status (running true/false): " + status.toBoolean())
  if (status.toBoolean() == false) {
      try {
        logHandler.debug("starting container $containerName")
        sh "docker start $containerName"
      } catch (all) {
        logHandler.debug("failed starting container $containerName")
        return false
      }
  } else {
      logHandler.debug("container $containerName was already running, doing nothing")
  }
  return true
}

def removeContainer(containerName) {
  try {
    sh "docker inspect --format='{{ .State.Running }}' " + containerName + " 2> /dev/null > .status"
  } catch (hudson.AbortException ae) {
    logHandler.debug("No container found by the name: ${containerName}, skipping removal step")
    return true
  }

  try {
    logHandler.debug("removing container $containerName")
    sh "docker rm -f $containerName"
  } catch (all) {
    logHandler.debug("failed removing container $containerName")
    return false
  }
  return true
}


//Postgres deploy required at build-time
def deployPostgres(containerName, forceRecreate=false) {
  if (forceRecreate) {
    removeContainer(containerName)

    def psApp = docker.image('postgres:latest')
    def psContainer = psApp.run(['--log-driver=syslog --log-opt tag=' + containerName + ' -e PGCONNECT_TIMEOUT=60 -p 5432:5432 --name ' + containerName + ' -d'])
    sh "docker ps"

    //sleeps to avoid some race conditions when updating the postgres db
    sh "sleep 20"
    sh 'docker exec -t ' + containerName + ' psql --username=postgres -c "CREATE USER admin PASSWORD \'admin\';" && sleep 10'
    sh 'docker exec -t ' + containerName + ' psql --username=postgres -c "CREATE DATABASE courseexchange OWNER admin;"'
    sh 'docker exec -t ' + containerName + ' psql --username=postgres -c "GRANT ALL ON DATABASE courseexchange TO admin;"'
  } else {
    startContainer(containerName)
  }
}

def slackNotify(channel, color, status, service, environment, url) {
    slackNotify(channel, color, status, service, environment, url, "")
}

def slackNotify(channel, color, status, service, environment, url, tag) {
  if (environment == null) {
    slackSend channel: channel, color: color, message: "Branch name ${env.BRANCH_NAME} does not follow convention feature/JIRA#_optional_description. Please change your branch name to allow a build to occur. \nJob: ${env.BUILD_URL}"
  } else {
    environment = environment.toUpperCase()
    tagmsg = tag ? " with tag ${tag}" : ""
    if (env.CHANGE_BRANCH) {
      slackSend channel: channel, color: color, message: "${environment} deploy of ${service}${tagmsg} ${status} for branch ${env.BRANCH_NAME} (${env.CHANGE_BRANCH} -> ${env.CHANGE_TARGET}) at endpoint: ${url} \nJob: ${env.BUILD_URL}"
    } else {
      slackSend channel: channel, color: color, message: "${environment} deploy of ${service}${tagmsg} ${status} for branch ${env.BRANCH_NAME} at endpoint: ${url} \nJob: ${env.BUILD_URL}"
    }
  }
}


/*
def validateInfo(url, port, protocol, artifact, commit) {
  println "DEBUG: accessing endpoint: $url/info"
  println "DEBUG: validating artifact: $artifact"
  println "DEBUG: validating commit: $commit"

  //protocol and port are ignored if url contains them. Also, we don't need authentication, so providing fake value
  def jenkins = new net.harniman.workflow.jenkins.Server("fake","fake", url, port, protocol)
  def response
  def slurper = new JsonSlurper()

  response = jenkins.getURL(url + "/info")
  println "DEBUG: response = $response"

  def result = slurper.parseText(response)
  assert(result.build.artifact == artifact)
  assert(result.git.commit.id == commit)

  println "INFO: All /info endpoint tests passed"
}
*/

def curlApi(url,timeout=5) {
      def cmd = "curl -i -k --max-time ${timeout} ${url} 2>/dev/null | head -n 1|cut -d\\  -f2"
      logHandler.debug("command: " + cmd)
      output = sh(returnStdout: true, script: cmd).trim()
      logHandler.debug("output: ${output}")
      return output == "200" || output == "302"
}

def checkAppStatus(url,sleeptime=5,timeout=10) {
      def status
      def n = 0
      while (n < 10) {
        n++
        status = curlApi(url, timeout)
        if(status) {
            return true
        }
        logHandler.debug("retry " + n)
        sleep sleeptime
      }
      return false
}

def checkAppHealth(url,sleeptime=5,timeout=10,health_status) {
      def response
      def n = 0
      while (n < 10) {
        n++
        response = getResponse(url, timeout)
        if(response =~ /${health_status}/ || response =~ /UP/) {
            return true
        }
        logHandler.debug("retry " + n)
        sleep sleeptime
      }
      return false
}

def getResponse(url,timeout=5) {
      def cmd = "curl -k -L --max-time ${timeout} ${url} 2>/dev/null"
      logHandler.debug("command: " + cmd)
      def output
      try {
          output = sh(returnStdout: true, script: cmd).trim()
      } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
          throw e
      } catch (all) {
          logHandler.debug("got bad response on endpoint ${url}")
      }
      logHandler.debug("output: ${output}")
      return output
}

def validateTag(service, tag) {
    validateTag(service, tag, "ccctechcenter")
}

def validateTag(service, tag, org) { //insures that a tag of the service is available in the registry. Requrires we adhere to our naming convention whereby imagename == servicename (works for everything except for redis and college-adaptor, hence the workarounds there
    def found_tag = false
    logHandler.debug("service: ${service}")
    logHandler.debug("tag: ${tag}")
    logHandler.debug("org ${org}")

    //workarounds for exceptions where service != image name
    if ( service =~ /redis/ ) { service = "redis" }
    if ( service =~ /.*-college-adaptor/ ) { service = "college-adaptor" }
    if ( service =~ /.*-apollo-client/ ) { service = "apollo-client" }
    if ( service =~ /.*-service-workers/ ) { service = "service-workers" }
    if ( service =~ /.*db-worker/ ) { service = "db-worker" }
    if ( service =~ /apply-ml-prediction/ || service =~ /apply-ml-training/ || service =~ /apply-ml-utils/ ) { service = "apply-ml-services" }

    //exclusions for services not hosted in our private registry
    if (service =~ /logspout/ || service =~ /logio/ || service =~ /postgres/ ||
        service =~ /docker-gc/ || service =~ /sftp/ || service =~ /network/ ||
        service =~ /directory/ || service =~ /newrelic-java-agent/ ||
        service =~ /test-mapping-container/ || service =~ /redis/ || service =~ /rabbitmq/ ||
        service =~ /ldapadmin/ || service =~ /db-worker/ ||
        service =~ /aws-elb/ || service =~ /rancher/ || service =~ /-cron$/ ||
        service =~ /elasticsearch/ || service =~ /elasticache/ || service =~ /rds/ || service =~ /keycloak-db/ ||
        service =~ /articulation-db/ || service =~ "cid2-db" || service =~ /docker-cleanup/ || service =~ /sentry/ ||
        service =~ /-lb$/ || service =~ /sonarqube/) {

        echo "DEBUG: excluding tag validation on non-private-registry hosted images"
        return true
    }

    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: '00733feb-a287-4875-94ba-e6b5c16c56a8', usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD']]) {
        cmd = "curl -q -u ${REGISTRY_USERNAME}:${REGISTRY_PASSWORD} https://${registry_url}/v2/${org}/${service}/tags/list 2>/dev/null | sed -e 's/[][]//g' -e 's/\"//g' -e 's/ //g' | tr '}' '\n'  | awk -F: '{print \$3}'"
        logHandler.debug("validateTag: cmd: " + cmd)
        output = sh(returnStdout: true, script: cmd).trim()
        logHandler.debug("tag list for service ${service}: " + output)
        tags = output.split(",")
        tags.each { testTag ->
            if (testTag == tag) { found_tag = true }
        }
        logHandler.debug("found_tag: ${found_tag}")
        return found_tag
    }
}

def getJson(response) {
    def jsonSlurper = new JsonSlurper()
    def json
    try {
       json = jsonSlurper.parseText(response)
    } catch (groovy.json.JsonException e) {
        logHandler.warn("did not get a valid json response: ${response}")
        println e.toString()
        json = null
    }
    return json
}

/*
* Retrieve logs from a named container in Rancher.
* May be invoked directly if only one stack is defined for the given env. Otherwise, use getContainerLogFromStack
*
* @param lines - Number of tailed lines to retrieve
* @param service - The name of the service, as specified in Rancher
* @param key - The rancher-env specific api key. Used both for api access, and to determine the scope the service resides within
* @param secret - The key secret
*/

def getContainerLog(lines, service, key, secret) { // use this format for Rancher envs that only have a single stack defined in them (eg: course-exchange)
    def ranchercmd = "#!/bin/sh -e\nrancher --access-key ${key} --secret-key ${secret} --url $rancher_url"
    echo "\n****************************************************\n********** BEGIN CONTAINER LOGS FOR ${service} **********\n****************************************************\n"
    retval = sh(returnStatus: true, script: "${ranchercmd} logs --tail ${lines} ${service}")
    echo "\n****************************************************\n*********** END CONTAINER LOGS FOR ${service} ***********\n****************************************************\n"
}

def getContainerLogFromStack(service, stack, key, secret) {
    getContainerLogFromStack(log_length, service, stack, key, secret)
}

/*
* Retrieve the desired number of lines of the container(application) log from the specified stack + service in Rancher
*
* @param lines - Number of tailed lines to retrieve
* @param service - The name of the service, as specified in Rancher
* @param stack - The name of the stack where the service resides
* @param key - The rancher-env specific api key. Used both for api access, and to determine the scope the stack + service resides within
* @param secret - The key secret
*/

def getContainerLogFromStack(lines, service, stack, key, secret) { // use this signature for Rancher envs that support multiple stacks (eg: coci2, admin2)
    def serviceId = this.getServiceId(service, stack, key, secret)
    if (serviceId != null) {
        if (container_log) {
            container_log = false
            return getContainerLog(lines, serviceId, key, secret)
        } else {
            return true
        }

    } else {
        return  "WARN: No Logs found for ${service}"
    }
}

/*
* Retrieve the rancher json definition for a stack
* @param service - a service name - used in rancher_url mapping calculation
* @param stack - The name of the stack where the service resides. Required for rancher envs that have multiple stacks (eg: CI and QA)
* @param key - The rancher-env specific api key.
* @param secret - The key secret
* @param environment - The target environment
*
* @return stack - the stack json definition
*/

def getStack(key, secret, stack, environment, service) {
    setupDeployEnv()
    setRancherUrl(environment, service)
    def ranchercmd = "#!/bin/sh -e\nrancher --access-key ${key} --secret-key ${secret} --url $rancher_url"
    def resp = sh(returnStdout:true, script: "${ranchercmd} inspect --type stack ${stack}").trim()
    if (!resp || resp == '') {
        logHandler.warn("No response received from Rancher for service ${service}")
        return null
    }

    def json = getJson(resp)
    return json
}

/*
* Retrieve all active services in a stack. Returns an array of service names
* @param service - a service name - used in rancher_url mapping calculation
* @param stack - The name of the stack where the service resides. Required for rancher envs that have multiple stacks (eg: CI and QA)
* @param key - The rancher-env specific api key.
* @param secret - The key secret
* @param environment - The target environment
*
* @return serviceNames - Array of active service names or null if no matching serviceId found
*/

def getActiveServices(key, secret, stack, environment, service) {
    setupDeployEnv()
    setRancherUrl(environment, service)
    def ranchercmd = "#!/bin/sh -e\nrancher --access-key ${key} --secret-key ${secret} --url $rancher_url"
    def json = getStack(key, secret, stack, environment, service)
    def activeServices = []

    if (json) {
        def serviceIds = json.serviceIds
        json = null //we need to null out this object after use, as it's not serializable
        def service_state = null
        for (def serviceId in serviceIds) {
            resp = sh(returnStdout:true, script: "${ranchercmd} inspect --type service ${serviceId}").trim()
            if (!resp || resp == '') {
                logHandler.warn("No response received from Rancher for serviceID ${serviceId}")
                break
            }

            json = getJson(resp)
            if (json) {
                service_state = json.state
                service_name = json.name
            } else {
                break
            }
            logHandler.debug("getActiveServices: ${serviceId} state: ${service_state} name: ${service_name}")
            json = null
            if (service_state == "active") {
                activeServices << service_name
            }
        }
        return activeServices
    }  else {
        logHandler.warn("getActiveServices: No valid json retrieved")
    }
}

/*
* Retrieve the rancher serviceId for a service + stack
* @param service - The name of the service, as specified in Rancher
* @param stack - The name of the stack where the service resides. Required for rancher envs that have multiple stacks (eg: CI and QA)
* @param key - The rancher-env specific api key.
* @param secret - The key secret
*
* @return serviceId - The serviceId of the stack-scoped service, or null if no matching serviceId found
*/

def getServiceId(service, stack, key, secret) {
    def ranchercmd = "#!/bin/sh -e\nrancher --access-key ${key} --secret-key ${secret} --url $rancher_url"
    def resp = sh(returnStdout:true, script: "${ranchercmd} inspect --type stack ${stack}").trim()
    if (!resp || resp == '') {
        logHandler.warn("No response received from Rancher for service ${service}")
        return null
    }

    def json = getJson(resp)
    if (json) {
        def serviceIds = json.serviceIds
        json = null //we need to null out this object after use, as it's not serializable
        def serviceName
        def foundId = null
        for (def serviceId in serviceIds) {
            resp = sh(returnStdout:true, script: "${ranchercmd} inspect --type service ${serviceId}").trim()
            if (!resp || resp == '') {
                logHandler.warn("No response received from Rancher for serviceID ${serviceId}")
                break
            }

            json = getJson(resp)
            if (json) {
                serviceName = json.name
            } else {
                break
            }
            json = null
            if (serviceName == service) {
                foundId = serviceId
            }
        }
        if (foundId) {
            logHandler.debug("Found match for service: ${service} serviceId: ${foundId}")
            return foundId
        } else {
            logHandler.warn("No match for service: ${service} found among serviceIds: ${serviceIds} in stack: ${stack}")
        }
    }  else {
        logHandler.warn("No valid json retrieved")
    }
    return null
}


/*
* Given a rancher-env specific key and secret, and a service name, return the details for that service
*
* @param service - The name of the service, as specified in Rancher
* @param key - The rancher-env specific api key.
* @param secret - The key secret
*/

def getServiceDetails(service, key, secret, stack) {
    def ranchercmd = "#!/bin/sh -e\nrancher --access-key ${key} --secret-key ${secret} --url $rancher_url"
    def resp
    if (!stack) { //support legacy callers where multiple stacks don't prevent this from working
        resp = sh(returnStdout:true, script: "${ranchercmd} inspect --type service ${service}").trim()
    }
    else {
        resp = sh(returnStdout:true, script: "${ranchercmd} inspect --type service ${stack}/${service}").trim()
    }
    if (!resp || resp == '') {
        logHandler.error("No response received from Rancher for service ${service}")
        return false
    }

    def json = readJSON returnPojo: true, text: resp
    return json
}

/*
* Given a rancher-env specific key and secret, return the container details for that env
*
* @param service - The name of the service, as specified in Rancher
* @param key - The rancher-env specific api key.
* @param secret - The key secret
* @param stack - The rancher stack
*
* @return json - the json object containing the container details
*/

def getContainers(service, key, secret, stack) {
    setupDeployEnv()
    def json
    try {
        def service_details = this.getServiceDetails(service, key, secret, stack)
        if (!service_details) {
            error("ERROR: could not get details for ${stack}/${service}.")
        }
        def environment_id = service_details.accountId
        logHandler.debug("environment_id: $environment_id")

        def resp = sh(returnStdout:true, script: "#!/bin/sh -e\n curl -u \"${key}:${secret}\" \
            -X GET \
            -H 'Accept: application/json' \
            -H 'Content-Type: application/json' \
            -d '{}' \
            \"${rancher_url}/v2-beta/projects/${environment_id}/containers?limit=1000\"")
        if (!resp || resp == '') {
            echo  "ERROR: No response received from Rancher for service ${service}"
            return null
        }
        json = readJSON returnPojo: true, text: resp
    } catch (Exception e) {
        slackSend channel: "#devops", color: "warning", \
        message: "ERROR: Issue getting containers for $service:\n$e\nJob: ${env.BUILD_URL}"
    }
    return json
}

/*
* Given a rancher-env specific key and secret, and a service name, return the container names
*
* @param service - The name of the service, as specified in Rancher
* @param environment - The name of the environment, typicall one of ci, qa, pilot, prod
* @param key - The rancher-env specific api key.
* @param secret - The key secret
*
* @return list of container names matching the service + environment provided
*/

def getContainerNameListForService(service, environment, key, secret, stack) {
    def container_details = getContainers(service, key, secret, stack)
    def name_list = []
    container_details.data.name.each {
        if (it ==~ /${stack}-${service}-[0-9].*/) {
            name_list.add(it)
        }
    }
    return name_list
}


/*
* Given a rancher-env specific key and secret, and a service name, perform the specified action. Note: supported only for actions that do not require a request body
* Note: this does not facilitate changing details of the sidekick configuration - it merely insures that the image is pulled from the registry, and redeployed according to the current config
*
* @param service - The name of the service, as specified in Rancher
* @param key - The rancher-env specific api key. Used both for api access, and to determine the scope the stack + service resides within
* @param secret - The key secret
* @param action - The desired action to perform - supported only for actions that do not require a request body
*/

def rancherApiCall(service, key, secret, action, stack) {
    setupDeployEnv()
    try {
        def service_details = this.getServiceDetails(service, key, secret, stack)
        if (!service_details) {
            error("ERROR: could not get details for ${stack}/${service}.")
        }
        def service_id = service_details.id
        logHandler.debug("service_id: $service_id")
        def environment_id = service_details.accountId
        logHandler.debug("environment_id: $environment_id")
        logHandler.debug("action: $action")
        logHandler.debug("service: $service")

        shNoEcho("curl -s -u \"${key}:${secret}\" \
            -X POST \
            -H 'Accept: application/json' \
            -H 'Content-Type: application/json' \
            -d '{}' \
            \"${rancher_url}/v2-beta/projects/${environment_id}/services/${service_id}/?action=${action}\" > /dev/null")
    } catch (Exception e) {
        slackSend channel: "#devops", color: "warning", \
        message: "ERROR: Issue running $action for $service:\n$e\nJob: ${env.BUILD_URL}"
    }
}

/*
* Given a rancher-env specific key and secret, and a service name, force an update of the sidekick container.
*
* @param service - The name of the service, as specified in Rancher
* @param key - The rancher-env specific api key. Used both for api access, and to determine the scope the stack + service resides within
* @param secret - The key secret
*/

def upgradeSidekick(service, key, secret) {
    upgradeSidekick(service, key, secret, null)
}

def upgradeSidekick(service, key, secret, stack) {
    upgradeSidekick(service, key, secret, stack, "prod")
}

def upgradeSidekick(service, key, secret, stack, environment) {
    upgradeSidekick(service, key, secret, stack, environment, "ccctechcenter.org")
}

def upgradeSidekick(service, key, secret, stack, environment, domain) {
    upgradeSidekick(service, key, secret, stack, environment, domain, "latest")
}

def upgradeSidekick(service, key, secret, stack, environment, domain, tag) {
    setupDeployEnv()
    setRancherUrl(environment, service, domain)
    try {
        def service_details = this.getServiceDetails(service, key, secret, stack)
        if (!service_details) {
            error("ERROR: could not get details for ${stack}/${service}.")
        }
        def service_id = service_details.id
        logHandler.debug("service_id: $service_id")
        def environment_id = service_details.accountId
        logHandler.debug("environment_id: $environment_id")
        def slc = service_details.secondaryLaunchConfigs

        if (slc && slc[0]) { //empty result means no sidekicks found for this adaptor
            def imageUuid = slc[0].imageUuid
            vals = imageUuid.split(":")
            image = vals[0] + ":" + vals[1]
            orig_tag = vals[2]
            logHandler.debug("original sidekick image tag: " + orig_tag)
            logHandler.debug("new sidekick image tag: " + tag)
            def builder = new JsonBuilder(slc[0])
            builder.content.imageUuid = image + ":" + tag  //update image to new tag

            def request = "{\"inServiceStrategy\":{\"launchConfig\":null, \"secondaryLaunchConfigs\":[" + builder.toPrettyString() + "]},\"toServiceStrategy\":null}"
            //echo "DEBUG: request: " + request
            builder = null //Otherwise ERROR: java.io.NotSerializableException: groovy.json.JsonBuilder
            shNoEcho("curl -s -u \"${key}:${secret}\" \
                -X POST \
                -H 'Accept: application/json' \
                -H 'Content-Type: application/json' \
                -d \'${request}\' \
                \"${rancher_url}/v2-beta/projects/${environment_id}/services/${service_id}/?action=upgrade\" > /dev/null")

            waitForServiceState(rancher_url, key, secret, stack, service, "upgraded")

            shNoEcho("curl -s -u \"${key}:${secret}\" \
                -X POST \
                -H 'Accept: application/json' \
                -H 'Content-Type: application/json' \
                -d '{}' \
                \"${rancher_url}/v2-beta/projects/${environment_id}/services/${service_id}/?action=finishupgrade\" > /dev/null")

            sleep 5
        }
    } catch (net.sf.json.JSONException je) {
        logHandler.debug("Ignoring exception on first-time deploy of $service - no sidekick exists yet")
        slackSend channel: "#devops", color: "warning", \
            message: "WARN: Issue force-upgrading sidekick for $service:\n$je\nLikely this is a first-time deploy of a sidekick-enabled $service\nJob: ${env.BUILD_URL}"
    }
}

//the following patterns represent containers which we're not able, or wanting, to record deploy information in newrelic for:
@Field List NEWRELIC_BLACKLIST = ["cowcheck", "aws-elb", "cleanup", "logspout", "college-adaptor", "rancher", "elasticache", "elasticsearch", "conductor-auth", "conductor-ui", "ccctc-crontab", "-rds", "-db", "-lb", "-cron"]

def recordDeployInNR(app_name, app_id, tag, changelog) {
    try {
        // Note: changelog can be multiline, but needs all newlines replaced with '\n'.
        // Description can be long, but does not support linebreaks.
        if (changelog == "") {
            try {
                changelog = sh(returnStdout: true, script: "git show --oneline --stat ${tag}").trim()
            } catch (hudson.AbortException ae) {
                changelog = sh(returnStdout: true, script: "git show --oneline --stat HEAD").trim()
            }
        }
        changelog = changelog.replaceAll("\n", "\\\\n")
        try {
            version = sh(returnStdout: true, script: "git tag -ln1 ${tag}").trim()
        } catch (hudson.AbortException ae) {
            version = tag
        }
        sha = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        description = version + " " + sha
        postOut = sh(returnStdout: true, script: """curl -X POST 'https://api.newrelic.com/v2/applications/${app_id}/deployments.json' \
           -H 'X-Api-Key:e9dbf1bb497b98934a594959016982f38267471f1142265' \
           -H 'Content-Type: application/json' \
           -d \
            '{
              "deployment": {
                "revision": "${tag}",
                "changelog": "${changelog}",
                "description": "${description}"
              }
            }'""")
        echo postOut
        if (postOut.contains("Error 500")) {
            throw new Exception()
        }
    } catch (Exception e) {
        logHandler.warn("Could not record deploy ${tag} for ${app_name} in New Relic\n" + e.toString())
        slackSend channel: "#devops", color: "warning", \
            message: "Could not record deploy ${tag} for ${app_name} in New Relic\nJob: ${env.BUILD_URL}"
    }
}

def recordDeployInSFX(app_name, tag, changelog, event_type = "deploy") {
    try {
        // Note: changelog can be multiline, but needs all newlines replaced with '\n'.
        // Description can be long, but does not support linebreaks.
        if (changelog == "") {
            try {
                changelog = sh(returnStdout: true, script: "git show --oneline --stat ${tag}").trim()
            } catch (hudson.AbortException ae) {
                changelog = sh(returnStdout: true, script: "git show --oneline --stat HEAD").trim()
            }
        }
        changelog = changelog.replaceAll("\n", "\\\\n")
        try {
            version = sh(returnStdout: true, script: "git tag -ln1 ${tag}").trim()
        } catch (hudson.AbortException ae) {
            version = tag
        }
        sha = sh(returnStdout: true, script: "git rev-parse HEAD").trim()
        repo_url = sh(returnStdout: true, script: "git config --get remote.origin.url").trim()
        signalFxAPIToken = ceEnv.getSingleSSMParameter('SignalFxAPIToken', 'qa')
        description = version + " " + sha
        postOut = sh(returnStdout: true, script: """curl -X POST 'https://ingest.us1.signalfx.com/v2/event' \
           -H 'X-SF-TOKEN: $signalFxAPIToken' \
           -H 'Content-Type: application/json' \
           -d \
            '[
              {
                  "category": "USER_DEFINED",
                  "dimensions": {
                    "service": "${app_name}"
                  },
                  "eventType": "${event_type}",
                  "properties": {
                    "revision": "${tag}",
                    "changelog": "${changelog}",
                    "description": "${description}",
                    "repo_url": "${repo_url}"
                  }
              }
              ]'""")
        echo postOut
        if (!postOut.contains("OK")) {
            throw new Exception()
        }
    } catch (Exception e) {
        logHandler.warn("Could not record deploy ${tag} for ${app_name} in SignalFx\n" + e.toString())
        slackSend channel: "#devops", color: "warning", \
            message: "Could not record deploy ${tag} for ${app_name} in SignalFx\nJob: ${env.BUILD_URL}"
    }
}

/*
* get current scale of a service
*
* @param service - The name of the service, as specified in Rancher
* @param stack - The name of the stack where the service resides
* @param key - The rancher-env specific api key. Used both for api access, and to determine the scope the stack + service resides within
* @param secret - The key secret
*/

def getServiceScale(stack, service, key, secret) {
    def serviceId = this.getServiceId(service, stack, key, secret)
    scale = sh returnStdout: true, script: "#!/bin/sh -e\nrancher --url $rancher_url --access-key ${key} --secret-key ${secret} inspect --format {{.scale}} ${stack}/${service}"
    scale = scale.trim()
    logHandler.debug("${stack}/${service} scale: ${scale}")
    if (scale.toString().isInteger()) {
        return scale
    }
    return null
}

/*
* Scale a service
*
* @param scale - Desired scale of a service
* @param service - The name of the service, as specified in Rancher
* @param stack - The name of the stack where the service resides
* @param key - The rancher-env specific api key. Used both for api access, and to determine the scope the stack + service resides within
* @param secret - The key secret
*/

def setServiceScale(scale, stack, service, key, secret) {
    def serviceId = this.getServiceId(service, stack, key, secret)
    if (serviceId != null) {
        def ranchercmd = "#!/bin/sh -e\nrancher --access-key ${key} --secret-key ${secret} --url $rancher_url"
        def resp = sh(returnStdout:true, script: "${ranchercmd} scale ${serviceId}=${scale}").trim()
        if (!resp || resp == '' || resp != serviceId) {
            logHandler.error("expected response during scaling call not retrieved: ${resp} != ${serviceId}")
        } else {
            logHandler.debug("successfully scaled service ${service} to ${scale}")
            return true
        }
    } else {
        logHandler.error("Unable to scale service ${service}. No serviceId found")
    }
    return false
}

def exportRancherEnv(Map config) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    setupDeployEnv()
    env.RANCHER_ACCESS_KEY = config.rancher_key
    env.RANCHER_SECRET_KEY = config.rancher_pass
    env.RANCHER_URL= config.rancher_url
    env.RANCHER_ENVIRONMENT=sh(returnStdout:true, script: "rancher environment ls --format '{{.ID}}'").trim()
  }
}

def refreshCatalogs(Map config) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    sh """
      set -x +e
      URL=\$(echo ${config.rancher_url} | sed -e "s#ccctechcenter.org.*#ccctechcenter.org/v1-catalog/templates?refresh\\&action=refresh#")
      curl -s -X POST -u ${config.rancher_key}:${config.rancher_pass} \$URL
      exit 0
    """
  }
}

def getCurrentCatalogVersion(Map config) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    exportRancherEnv(config)
    VERSION = sh(returnStdout: true, script:"""
        rancher stack ls --format json | jq --slurp -rj --arg name ${config.stack} '.[] | select(.Stack.name == "\\(\$name)") | .Catalog'
        """)
    echo "${config.stack} : version = ${VERSION}"
    return VERSION
  }
}

def installCatalog(Map config) {
  config.timeout = config?.timeout ?: 10
  config.group = config?.group ?: "-new"
  config.rename_original = config?.rename_original ?: false
  new_stack_name = config.rename_original ? config.stack : "${config.stack}-${config.group}"
  old_stack_name = config.rename_original ? "${config.stack}-${config.group}" : config.stack
  logHandler.debug("Deploying stack ${new_stack_name} from catalog ${config.catalog}")
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    try {
      timeout(time:config.timeout, unit:'MINUTES') {
        exportRancherEnv(config)
        refreshCatalogs(config)
        env.timeoutSeconds = config.timeout * 60
        removeRancherStack(config, "${new_stack_name}") //in case stack alredy exists
        sh """
          set -ex
          rancher --wait --wait-state healthy \
                  --wait-timeout ${timeoutSeconds} \
                  catalog install -a ${config.answers} \
                  --name "${new_stack_name}" "${config.catalog}"

          rancher deactivate --type=stack "${old_stack_name}" || true
        """
      }
    } catch (Exception e) {
      sh """
        rancher rm --type=stack "${new_stack_name}"
      """
      logHandler.error("Exception during installCatalog: caught error on install: " + e.toString())
      throw e
    }
  }
  return "${new_stack_name}"
}


def renameRancherStack(Map config, oldName, newName) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    exportRancherEnv(config)
    sh """
      set -ex
      ID=\$(rancher stacks ls --format '{{.ID}} {{.Stack.Name}}' | \
            grep " ${oldName}\$" | awk {'printf \$1'})

      URL=\$(echo ${config.rancher_url} | sed -e "s#ccctechcenter.org.*#ccctechcenter.org/v2-beta/projects/\${RANCHER_ENVIRONMENT}/stacks/\${ID}#")
      RESULT=\$(curl -s -X GET -u ${config.rancher_key}:${config.rancher_pass} \$URL |
                jq --arg newName ${newName} '.name = "\\(\$newName)"' |
                curl -H "Content-Type: application/json" -s -X PUT \
                     -u ${config.rancher_key}:${config.rancher_pass} \
                     --data-binary @- \$URL)
      echo \$RESULT | jq -jr '.type' | grep -iv error
    """
  }
}

def removeRancherStack(Map config, stackName) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    exportRancherEnv(config)
    sh """
      set -x +e
      rancher stacks ls --format ':{{.Stack.Name}}:' | grep ":${stackName}:"
      [ \$? -eq 0 ] || exit 0

      rancher rm --type=stack "${stackName}" || true

      while true; do
        echo "waiting for delete to complete..."
        sleep 5
        rancher stacks ls --format ':{{.Stack.Name}}:' | grep ":${stackName}:" || break
      done
      exit 0
    """
  }
}

def activateRancherStack(Map config, stackName) {
  wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: config.rancher_pass, var: 'SECRET']]]) {
    exportRancherEnv(config)
    sh """
      set -x
      rancher --wait --wait-state healthy activate --type=stack "${stackName}"
    """
  }
}

def waitForStackUpdateComplete(name) {
    getStackStatus(name, "UPDATE")
}

def getStackStatus(name, action) {
    def copied = ceBuild.copyScript("stack.sh")
    status = "STARTED"
    while ({
        sleep 10
        status = sh returnStdout: true, script: "./stack.sh --status ${name}"
        logHandler.debug("status: ${status}")
        status ==~ /.*PROGRESS[\r\n]*/
    }()) continue
    if (copied) {
        ceBuild.removeCopiedScript("stack.sh")
    }
    return status ==~ /${action}_COMPLETE[\r\n]*/
}

def stackExists(name) {
    def copied = ceBuild.copyScript("stack.sh")
    exists = sh returnStdout: true, script: "./stack.sh --exists ${name}"
    logHandler.debug("stack exists: **${exists}**")
    if (copied) {
        ceBuild.removeCopiedScript("stack.sh")
    }
    return exists == "true"
}

def createOrUpdateCfnStacks(environment) {
    def stacks = []
    findFiles(glob: "*-${environment}-*").each {
        stacks.push(it.name)
    }
    logHandler.info("found stack parameter files: ${stacks}")
    createOrUpdateStacks(stacks)
}

def createOrUpdateStacks(stackParameterFiles, waitForCompletion=true) {
    createOrUpdateStacks(stackParameterFiles, waitForCompletion, false)
}

def createOrUpdateStacks(stackParameterFiles, waitForCompletion, manualChangesetExecution) {
    def stackAction = [:]
    def copied = ceBuild.copyScript("stack.sh")

    stackParameterFiles.each {
        if (stackExists(it)) {
            logHandler.info("Updating stack ${it}")
            if (manualChangesetExecution == true) {
                logHandler.debug("manualChangesetExecution enabled")
                sh script: "./stack.sh --changeset ${it}"
                //pause here now
                input "Review and execute changeset for CloudFormation stack ${it} in AWS. Or decline the changeset and Abort"
                update = 0
            } else {
                update = sh returnStatus: true, script: "./stack.sh --update ${it}"
            }
            if (update == 0) {
                stackAction[it] = "UPDATE"
            } else if (update == 3) {
                logHandler.debug("received no-updates exit code")
            } else {
                error("failed to start update for stack ${it}")
            }
        } else {
            logHandler.info("Creating stack ${it}")
            sh "./stack.sh --create ${it}"
            stackAction[it] = "CREATE"
        }
    }

    if (waitForCompletion) {
        if (stackAction.size() > 0) {
            waitUntil {
                try {
                    timeout(60) {
                        stackAction.each{ name, action ->
                            if (getStackStatus(name, action)) {
                                logHandler.info("stack ${name} ${action} completed successfully")
                            } else {
                                sh "./stack.sh --events ${name}"
                                error("stack ${name} failed to ${action}")
                            }
                        }
                    }
                } catch (error) {
                    timeout(60) {
                      input "Do you wish to keep waiting for the stack update?"
                      false
                    }
                }
                true
            }
        }
    }
    if (copied) {
        ceBuild.removeCopiedScript("stack.sh")
    }
}

def cancelStackUpdate(name) {
    def copied = ceBuild.copyScript("stack.sh")
    cancelled = sh returnStdout: true, script: "./stack.sh --cancel ${name}"
    logHandler.debug("stack update cancelled: **${cancelled}**")
    if (copied) {
        ceBuild.removeCopiedScript("stack.sh")
    }
    return cancelled == "true"
}

def waitForResource(stack, resource, status = '*', infiniteWait = 'false') {
  sh """
    getStackStatus() {
    aws cloudformation describe-stacks \
       --stack-name ${stack} | jq -r '.Stacks[0].StackStatus'
    }

    until aws cloudformation describe-stack-resource \
        --stack-name ${stack} \
        --logical-resource-id ${resource} \
        --output text --query 'StackResourceDetail.ResourceStatus' \
        | grep -q -E "${status}"
    do
    if [[ "${infiniteWait}" != "true" ]]; then
      status=\$(getStackStatus)
      if [[  "\${status}" != UPDATE_IN_PROGRESS ]]; then
        exit 1
      fi
    fi
    echo "waiting for ${resource} in ${stack}..."
    sleep 30
    done
  """
}

def getPhysicalResourceId(stack, logicalId) {
    ID = sh(returnStdout: true, script:"""
        aws cloudformation describe-stack-resource \
            --stack-name ${stack} \
            --logical-resource-id ${logicalId} | jq -rj '.StackResourceDetail.PhysicalResourceId'
        """)
    return ID
}

def getWaitHandle(stack, handle) {
    return getPhysicalResourceId(stack, handle)
}

// allowed statuses are 'SUCCESS' or 'FAILURE'
def notifyWaitHandle(status, stack, handle) {

    def signal = readJSON returnPojo: true, text: '{}'
    signal.Status = status
    signal.Reason = "Configuration Complete"
    signal.UniqueId = sh(returnStdout: true, script: 'date +"%s"').trim()
    signal.Data = "Application has completed configuration."

    def signalFile = pwd(tmp:true) + "/signal." + signal.UniqueId
    writeJSON(file:signalFile, json:signal)
    HANDLE = getWaitHandle(stack, handle)

    sh """
      set -xe
      curl -T "${signalFile}" "${HANDLE}"
    """
}

//don't write sh call to log unless the log level is DEBUG
def shNoEcho(cmd) {
    if (logHandler.castLogLevel(LOG_LEVEL) == logHandler.DEBUG_LEVEL) {
        sh('#!/bin/sh -x\n' + cmd)
    }
    else {
        sh('#!/bin/sh -e\n' + cmd)
    }
}
