#!groovy

// Gregory Pageot
// 2019-01-05

// To get a perforce ticket, use the following command "p4 login -p" from a connected P4V

// To Display fingerprint accepted, use the following command "p4 trust -l" from a connected P4V

node
{
	try{

		def epicServerURL = P4_URL
		def epicP4Fingerprint = P4_FINGERPRINT
		def epicServerCredential = P4_CREDENTIAL
		def epicP4UserName = P4_USERNAME
		def epicP4WorkspaceName = P4_WORKSPACENAME
		def epicP4Encoding = P4_UNICODE_ENCODING

		stage('Get perforce')
		{
			echo "Testing P4 connection with following setting"
			echo "Server URL : ${epicServerURL}"
			echo "Fingerprint : ${epicP4Fingerprint}"
			echo "Credential : ${epicServerCredential}"
			echo "User Name : ${epicP4UserName}"
			echo "Workspace Name : ${epicP4WorkspaceName}"
			echo "Encoding : ${epicP4Encoding}"

			// Register trust value for Epic SSL connection
			bat """p4 -p ${epicServerURL} trust -f -i ${epicP4Fingerprint}"""

			def epicP4LoginOptions = "-P ${epicServerCredential} -u ${epicP4UserName} -c ${epicP4WorkspaceName} -p ${epicServerURL} -C ${epicP4Encoding}"

			// Update epic perforce workspace
			bat """p4 ${epicP4LoginOptions} sync"""
		}

		//slackSend color: 'good', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} succeed (${env.BUILD_URL})"
	}
	catch (exception)
	{
		//slackSend color: 'bad', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} failed (${env.BUILD_URL})"
		throw exception
	}
}
