#!groovy

// Gregory Pageot
// 2018-10-22

node
{
	try{
		def perforceWorkspaceName = P4_WORKSPACE_NAME
		def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
		def perforceUnicodeMode = P4_UNICODE_ENCODING

		stage('Get perforce')
		{
			echo "Testing P4 connection with following setting"
			echo "Workspace Name : ${perforceWorkspaceName}"
			echo "credential : ${perforceCredentialInJenkins}"
			echo "encoding : ${perforceUnicodeMode}"

			// Using autoClean here in order to make sure to clean the folder first			
			checkout perforce(
					credential: perforceCredentialInJenkins,
					//populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					populate: autoClean(delete: true, modtime: false, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, replace: true, tidy: false),
					workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false))
		}

		slackSend color: 'good', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} succeed (${env.BUILD_URL})"
	}
	catch (exception)
	{
		slackSend color: 'bad', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} failed (${env.BUILD_URL})"
		throw exception
	}
}
