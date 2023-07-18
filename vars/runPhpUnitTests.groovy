/* Run phpunit tests from a Laravel project
*
*  @param db_host  - set to the name of the db host container - the test runtime needs to attach to this
*  @param db_username  - optional: current default works for both php projects
*  @param db_password  - optional: current default works for both php projects
*  @param xdebug  - [true | false] enable xdebug in php. Required if code coverage is to be generated
*  @param bin - [phpunit | pest ] select normal phpunit or pest phpunit extension
*
*  Example: runPhpUnitTests db_host: "cid2-db"
*/
def call(Map config) {
    //check for nulls and set defaults
    config.db_host = config?.db_host ?: "cid2-db"
    config.db_username = config?.db_username ?: "coci"
    config.db_password = config?.db_password ?: "coci"
    config.xdebug = config.xdebug != null ? config.xdebug : true
    config.bin = config.bin != null ? config.bin : "phpunit"
    config.php_version = config.php_version != null ? config.php_version : 7.0
    //tool name: 'phpunit7.2-reqs' //manually installed one-time
    sh "sudo update-alternatives --set php /usr/bin/php${config.php_version}"
    def phpUnit_result = 0
    if (config.xdebug == true) {
        sh "sudo sed -i 's/^;*//g' /etc/php/${config.php_version}/cli/conf.d/20-xdebug.ini"
        phpUnit_result = sh(returnStatus: true, script: "DB_USERNAME=${config.db_username} DB_PASSWORD=${config.db_password} DB_HOST=\$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${config.db_host}) ./Laravel/vendor/bin/${config.bin} --configuration Laravel/phpunit.xml --colors=never --log-junit results.xml --coverage-clover coverage-clover.xml")
        step([
            $class: 'CloverPublisher',
            cloverReportDir: '',
            cloverReportFileName: 'coverage-clover.xml'
        ])
    } else {
        sh "sudo sed -i 's/^z/;z/g' /etc/php/${config.php_version}/cli/conf.d/20-xdebug.ini"
        phpUnit_result = sh(returnStatus: true, script: "DB_USERNAME=${config.db_username} DB_PASSWORD=${config.db_password} DB_HOST=\$(docker inspect --format='{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ${config.db_host}) ./Laravel/vendor/bin/${config.bin} --configuration Laravel/phpunit.xml --log-junit results.xml")
    }
    junit "**/results.xml"
    echo "DEBUG: phpUnitTest Results: ${phpUnit_result}"
    if (phpUnit_result != 0) {
       currentBuild.result = "UNSTABLE"
    }
}
