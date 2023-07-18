def call(String environment = 'ci', String test_version="latest", String test_folder="build-tests") {
    try {
        sh "mkdir -p target/test/postman/newman"
        sh "docker pull registry.ccctechcenter.org:5000/ccctechcenter/api-gateway-test:${test_version}"
        def test_retcode = sh returnStatus: true, script: "docker run --rm -v $WORKSPACE/target/test/postman/newman:/etc/newman/newman registry.ccctechcenter.org:5000/ccctechcenter/api-gateway-test:${test_version} -e ${environment} -f ${test_folder}"
        echo "DEBUG: test_retcode: ${test_retcode}"
        sh "sudo chown -R jenkins:jenkins target/test/postman/newman" //update owner on postman results directory as docker created files as root there
        junit_result_files = findFiles glob: 'target/test/postman/newman/*.xml'
        if (junit_result_files.length > 0) {
            junit allowEmptyResults: true, testResults: 'target/test/postman/newman/*.xml'
            archiveArtifacts 'target/test/postman/newman/*.xml'
        } else {
            echo "WARN: no junit results found during api-gateway-test postman test run"
            currentBuild.result = "UNSTABLE"
        }
    } catch (Exception e) {
        echo "WARN: runApiGatewayTests exception: " + e.toString()
        currentBuild.result = 'UNSTABLE'
    }
}

