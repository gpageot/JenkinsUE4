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
		def perforceWorkspaceName = 'P4_WORKSPACE_NAME'
		def perforceCredentialInJenkins = 'JENKINS_P4_CREDENTIAL'

		stage('Get perforce')
		{
			checkout perforce(
                credential: perforceCredentialInJenkins,
                populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
                workspace: staticSpec(charset: 'none', name: perforceWorkspaceName, pinHost: false))
		}

		stage( 'FillDDC' )
		{
			// Compile the game
			// Same as Visual studio NMake
			bat """
				\"${engineLocalPath}\\Engine\\Binaries\\Win64\\UE4Editor.exe\" -Project=\"${projectLocalPath}\\${projectName}.uproject\" -run=DerivedDataCache -fill
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

