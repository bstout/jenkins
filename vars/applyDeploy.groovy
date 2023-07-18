
def getApplyDeployEnvironment(env, branch, defVal, prod_deploy_group = 'admin-deploy') {
    if (defVal == '(default)' || !(['ci', 'test', 'pilot', 'prod'].contains(defVal))) {
        env.environment = ceEnv.getEnvironmentFromBranchName(env.BRANCH_NAME, 'apply-services')
    } else {
        env.environment = defVal;
    }

    if (env.environment == null) {
        currentBuild.result = 'ABORTED'
        error ("skipping build: unsupported branch ${env.BRANCH_NAME}");
        return null;
    }

    return env.environment
}

def getApplyDeployTag(env) {
    //upate to use standard, branch-based deploy tagging.
    def tag = null;
    tag = ceBuild.getDeployTag(env.service, env.BRANCH_NAME)
    if (tag == null) {
        currentBuild.result = 'FAILED';
        error 'Unable to determine deploy_tag';
    }

    return tag;
}
