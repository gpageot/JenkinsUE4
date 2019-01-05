#!groovy

// Gregory Pageot
// 2018-07-23

node
{
	try{
		// Perforce workspace mapping
		//		//DEPOT_NAME/UE4/Trunk/...	//PerforceWorkspaceRoot/UE4/Trunk/...
		//		//DEPOT_NAME/PROJECT_NAME/...	//PerforceWorkspaceRoot/UE4/Projects/PROJECT_NAME/...

		def engineLocalPath = ENGINE_LOCAL_PATH
		def projectName = PROJECT_NAME
		def projectLocalPath = PROJECT_LOCAL_PATH
		def perforceWorkspaceName = P4_WORKSPACE_NAME
		def perforceWorkspaceNameProject = P4_WORKSPACE_NAME_PROJECT
		def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
		def archiveLocalPath = ARCHIVE_PATH
		
		stage('Get perforce')
		{
			checkout perforce(
				credential: perforceCredentialInJenkins,
				populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
				workspace: staticSpec(charset: 'none', name: perforceWorkspaceName, pinHost: false)
				)
		}

		stage('Get perforce(Project)')
		{
			if(perforceWorkspaceNameProject != "")
			{
				checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					workspace: staticSpec(charset: 'none', name: perforceWorkspaceNameProject, pinHost: false)
					)
				
			}
		}

		stage( 'Package' )
		{
			def compilationTarget = "Development"
			def compilationPlatform = "Win64"
			def mapList = ""

			// Package the game
			// TODO : explains each options
			// WARNING : the file "Engine\Binaries\DotNET\UnrealBuildTool.xml" should have the filetype manually set to "text+w"
			bat """
				cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
				RunUAT.bat BuildCookRun -Project=\"${projectLocalPath}\\${projectName}.uproject\" -nocompileeditor -noP4  -package -build -compile -cook -stage -archive -archivedirectory=\"${archiveLocalPath}\\Build${BUILD_NUMBER}\" -clientconfig=${compilationTarget} -serverconfig=${compilationTarget} -targetplatform=${compilationPlatform} -map=${mapList} -unattended -buildmachine -nocodesign
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
