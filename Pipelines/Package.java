#!groovy

// Gregory Pageot
// 2018-07-23

node
{
	try{
		// Perforce workspace mapping
		//		//DEPOT_NAME/UE4/Trunk/...	//PerforceWorkspaceRoot/UE4/Trunk/...
		//		//DEPOT_NAME/PROJECT_NAME/...	//PerforceWorkspaceRoot/UE4/Projects/PROJECT_NAME/...

		def projectLocalPath = "D:\\Jenkins\\Workspace\\UE4\\Projects\\PROJECT_NAME"
		def projectName = "PROJECT_NAME"
		def engineLocalPath = "D:\\Jenkins\\Workspace\\UE4\\Trunk"
		def archiveLocalPath = "D:\\Jenkins\\Shared"
		def perforceWorkspaceName = 'P4_WORKSPACE_NAME'
		def perforceCredentialInJenkins = 'JENKINS_P4_CREDENTIAL'
		
		stage('Get perforce')
		{
			checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					workspace: staticSpec(charset: 'none', name: perforceWorkspaceName, pinHost: false))
		}

		stage( 'Package' )
		{
			def archiveLocalPath = "${archiveLocalPath}\\${projectName}\\Packages"
			def compilationTarget = "Development"
			def compilationPlatform = "Win64"
			def mapList = ""

			// Package the game
			// TODO : explains each options
			bat """
				cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
				RunUAT.bat BuildCookRun -Project=\"${projectLocalPath}\\${projectName}.uproject\" -nocompileeditor -noP4  -package -build -compile -cook -stage -archive -archivedirectory=\"${archiveLocalPath}\" -clientconfig=${compilationTarget} -serverconfig=${compilationTarget} -targetplatform=${compilationPlatform} -map=${mapList} -unattended -buildmachine -nocodesign
				"""
		}

		slackSend color: 'good', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} succeed (${env.BUILD_URL})"
	}
	catch (exception)
	{
		slackSend color: 'bad', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} failed (${env.BUILD_URL})"
		throw exception
	}
}
