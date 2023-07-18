/* Call a rainforest test suite and parse the results. The build result will be set to either PASSED, or UNSTABLE (test failures)
*
* Example: runRainforestTests("coci2", "pilot", "integration") - run the coci2 integration tests in pilot, with the default browser and a known token
*
* @param site - REQUIRED: the name of the site to test
* @param environment - REQUIRED: a valid environment for the site. environment Id's may be retrieved from rainforest and added to the map
* @param tag - a valid tag matching a set of tests in rainforest for the site being tested
* @param browser - a comma-separted list of valid browser names, as defined by rainforest
* @param token - a valid rainforest api token
* @param crowd - select automation or crowd of testers. Accepts the following inputs: [default|automation|automation_and_crowd|on_premise_crowd]
*/

def call(String site, String environment, String tag="integration", String browser="chrome_1440_900", String token="1dfb6f92cf073dfd9b88a3152bd3aff8", String crowd="automation") {

    println "DEBUG: Calling Rainforest tests with the following values:"
    println "DEBUG: site ${site}"
    println "DEBUG: environment ${environment}"
    println "DEBUG: tag ${tag}"
    println "DEBUG: browser ${browser}"
    println "DEBUG: crowd ${crowd}"

    def site_environments = [:]
    site_environments["coci2"] = ["ci":9299, "uat":7472, "qa":7472, "qa2": 10412, "pilot":9303]
    site_environments["cid"] = ["ci":10831, "qa":7471, "pilot":10225]
    environment_id = site_environments."${site}"."${environment}"

    def sites = [:]
    sites["coci2"] = 6219
    sites["cid"] = 6174
    site_id = sites."${site}"

    println "DEBUG: environment_id ${environment_id}"
    println "DEBUG: site_id ${site_id}"

    tool "rainforest-cli"
    rainforestHome = tool "rainforest-cli"
    env.PATH = "${rainforestHome}:${env.PATH}"

    timeout(time: 60, unit: 'MINUTES') {
        rainforest_cli_result = sh returnStatus: true, script: "rainforest --token ${token} run --tag ${tag} --browser ${browser} --junit-file results.xml --site-id ${site_id} --environment-id ${environment_id} --crowd ${crowd}"
    }

    if (rainforest_cli_result != 0) {
        echo "DEBUG: non-zero return code from rainforest test run, rainforest_cli_result=" + rainforest_cli_result
        echo "DEBUG: continuing build to let results parser determine build status"
    }
    junit 'results.xml'
    archiveArtifacts 'results.xml'
}
