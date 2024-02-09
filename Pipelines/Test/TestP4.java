#!groovy

// Gregory Pageot
// 2018-10-22
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Test connection to your local perforce server using credential you prepared for jenkins
//
// Jenkins job parameter:
// P4_WORKSPACE_NAME: String			Name of the perforce workspace use buy this job, need to be made in advance
// JENKINS_P4_CREDENTIAL: String		Add a credential with: Credentials > Jenkin (Global) > Global credentials > Add Credentials > Perforce Password Credential
// P4_UNICODE_ENCODING: String			If server if not set to support unicode, set it to "auto"
//
// WARNING:
// P4 plugin need to be installed
// slack plugin need to be installed

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

			// Use autoClean here in order to make sure to clean the folder first			
			// Use syncOnly for quick test and avoid java heap space issue			
			checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					//populate: autoClean(delete: true, modtime: false, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, replace: true, tidy: false),
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
