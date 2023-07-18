def call(Map config) {
    //check for nulls and set defaults
    config.environment = config?.environment ?: "ci"
    config.test_folder = config?.test_folder != null ? config?.test_folder : "build-tests"
    config.basename = config?.basename ?: "CollegeAdaptor-sites"
    config.image_name = config?.image_name ?: "postman-api-test"
    config.image_tag = config?.image_tag ?: "latest"
    config.quiet = config?.quiet != null ? config.quiet : true
    config.env_var_map = config?.env_var_map ?: null

    script="docker run --rm -v ${WORKSPACE}/target/test/postman/newman:/etc/newman/newman registry.ccctechcenter.org:5000/ccctechcenter/${config.image_name}:${config.image_tag} environment=${config.environment} basename=${config.basename} folder='${config.test_folder}'"
    config.env_var_map.each{ k, v ->
        script="${script} ${k}=\"${v}\""
    }
    if (config.quiet == true) {
        script="#!/bin/sh -e\n${script}" // hide command from logs. Can be used to not display secrets.
    }

    sh "mkdir -p target/test/postman/newman"
    sh "docker pull registry.ccctechcenter.org:5000/ccctechcenter/${config.image_name}:${config.image_tag}"
    sh returnStatus: true, script: script
    sh "sudo chown -R jenkins:jenkins target/test/postman/newman" //update owner on postman results directory as docker created files as root there
    junit 'target/test/postman/newman/*.xml'
}
