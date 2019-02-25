#!groovy

// Gregory Pageot
// 2018-07-23

def GetChangelistsDesc()
{
	if(currentBuild.changeSets.size() < 1)
	{
		return 'No Changes'
	}

	String checkoutReport = ""
	for(def items : currentBuild.changeSets)
	{
		for(def item : items)
		{
			checkoutReport += "${item.getAuthor()} ${item.getChangeNumber()} \"${item.getMsg()}\"\n"
		}
	}

	return checkoutReport
}

def GetPreviousBuildStatusExceptAborted()
{
	def previousBuild = currentBuild.getPreviousBuild()
	while(previousBuild != null)
	{
		def status = previousBuild.getResult().toString()
		if(status != 'ABORTED')
			return status

		previousBuild = previousBuild.getPreviousBuild()
	}
	return 'unknown'
}

node
{
	try{
		// Path to Jenkins local engine folder for the given perforce workspace
		def engineLocalPath = ENGINE_LOCAL_PATH
		// Path to Jenkins local project folder for the given perforce workspace
		def projectLocalPath = PROJECT_LOCAL_PATH
		// Project Name
		def projectName = PROJECT_NAME
		// Name of the perforce workspace use by this pipeline
		def perforceWorkspaceName = P4_WORKSPACE_NAME
		// Jenkins credential ID to use the given perforce workspace
		def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
		// Encoding for the given perforce workspace
		def perforceUnicodeMode = P4_UNICODE_ENCODING

		stage('Get perforce')
		{
			checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: false, revert: true),
					workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false))
		}

		stage( 'FillDDC' )
		{
			// Compile the game
			// Same as Visual studio NMake
			bat """
				\"${engineLocalPath}\\Engine\\Binaries\\Win64\\UE4Editor.exe\" -Project=\"${projectLocalPath}\\${projectName}.uproject\" -run=DerivedDataCache -fill
				"""
		}

		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def previousBuildFailed = previousBuildSucceed == false
		def buildFixed = previousBuildFailed
		slackSend color: 'good', message: "${buildFixed?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${buildFixed?'fixed':'succeed'} (${env.BUILD_URL})"
	}
	catch (exception)
	{
		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def buildFirstFail = previousBuildSucceed
		slackSend color: 'bad', message: "${buildFirstFail?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${buildFirstFail?'failed':'still failing'} (${env.BUILD_URL})\n${buildFirstFail?GetChangelistsDesc():''}"
		throw exception
	}
}
