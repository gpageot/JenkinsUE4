#!groovy

// Gregory Pageot
// 2018-07-23
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Package the project
//
// Jenkins job parameter:
// ENGINE_LOCAL_PATH: String
// PROJECT_LOCAL_PATH: String
// PROJECT_NAME: String
// P4_WORKSPACE_NAME: String
// JENKINS_P4_CREDENTIAL: String
// P4_UNICODE_ENCODING: String
// FULL_REBUILD: Boolean
// PROJECT_ARCHIVE_PATH: String
// COMPILATION_TARGET: String
// COMPILATION_PLATFORM: String
// PACKAGE_USE_PAK: Boolean
// UNSHELVE_CHANGELIST: String

// WARNING:
// In order to have thje Zip step to work, you need to install the plugin:
// "Pipeline Utility Steps"

// Need permission for:
//
// org.codehaus.groovy.runtime.DefaultGroovyMethods deleteDir java.io.File

// TODO: 
// P4_LABEL_NAME: String
// P4_LABEL_DESC: String

def GetListOfClientFile(P4CommandResult)
{
	String result = ""
	for(def item : P4CommandResult)
	{
		for (String key : item.keySet())
		{
			if(key == 'clientFile')
			{
				value = item.get(key)
				result += value + "\n"
			}
		}
	}
	return result
}

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
	//	Set authors = currentBuild.changeSets.collect({ it.items.collect { it.author.toString() } })
	//	Set uniqueAuthors = authors.unique()

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
	try
	{
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
		// If true, the perforce workspace will do a force clean
		def optionFullRebuild = FULL_REBUILD.toBoolean()
		// Local path where to upload the package
		def archiveLocalPathRoot = PROJECT_ARCHIVE_PATH
		// Compilation target for the package, example: "Development"
		def compilationTarget = COMPILATION_TARGET
		// Compilation platform for the package, example: "Win64"
		def compilationPlatform = COMPILATION_PLATFORM
		// List of maps to include in the package (Note that by default the engine will include some maps)
		def mapList = ""
		// If true, will activate the 'pak' step of UE4 packaging
		def packageUsePAK = PACKAGE_USE_PAK.toBoolean()
		// If not empty, will try to unshelve from perforce
		def optionUnshelveCL = UNSHELVE_CHANGELIST

		stage('Get perforce')
		{
			def populateOption
			if(optionFullRebuild)
			{
				echo "delete directory ${projectLocalPath}"
				def destinationDirPath = new File(projectLocalPath)
				destinationDirPath.deleteDir();

				// Currently failing with "ERROR: P4: Task Exception: java.io.IOException: Unable to delete directory [Perforce_Workspace_Folder]."
				populateOption = forceClean(have: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true)
			}
			else
			{
				// Set quiet to false in order to have output
				populateOption = syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: false, revert: true)
			}

			checkout perforce(
				credential: perforceCredentialInJenkins,
				populate: populateOption,
				workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)
				)
		}

		def p4 = p4 credential: perforceCredentialInJenkins, workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)

		stage( 'Prepare' )
		{
			// Edit files necessary for RunUAT batch
			echo "edit AutomationTool.exe.config"
			def editedFiles = p4.run('edit',
				"${engineLocalPath}/Engine/Binaries/DotNET/AutomationTool.exe.config".toString()
				)
			echo GetListOfClientFile(editedFiles)

			// If not empty, will unshelve given CL from perforce
			if(optionUnshelveCL != "")
			{
				p4unshelve( 
					credential: perforceCredentialInJenkins,
					shelf: optionUnshelveCL, 
					workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false))
			}
		}

		stage( 'Package' )
		{
			def packageOptions = ""
			if(packageUsePAK)
			{
				packageOptions = "-pak"
			}

			//  -nocompileeditor
			// Package the game
			bat """
				cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
				RunUAT.bat BuildCookRun -Project=\"${projectLocalPath}\\${projectName}.uproject\" -noP4 -package -build -compile -cook -stage -archive ${packageOptions} -archivedirectory=\"${archiveLocalPathRoot}\" -clientconfig=${compilationTarget} -serverconfig=${compilationTarget} -targetplatform=${compilationPlatform} -map=${mapList} -unattended -buildmachine -nocodesign
				"""
		}

		stage( 'Zip' )
		{
			// Optional: Zip the package in order to speed up file transfer over network
			def packageFolderName = compilationPlatform
			if(compilationPlatform == "Win64")
			{
				packageFolderName = "WindowsNoEditor"
			}

			def optionalUnshelveCL = ""
			if(optionUnshelveCL != "")
			{
				optionalUnshelveCL = "_${optionUnshelveCL}"
			}

			def packageLocalPath = "${archiveLocalPathRoot}\\${packageFolderName}"
			def archiveZipLocalPath = "${archiveLocalPathRoot}\\${projectName}_${compilationTarget}_${compilationPlatform}_${env.BUILD_NUMBER}${optionalUnshelveCL}.zip"

			echo "Zipping to: ${archiveZipLocalPath}"
			zip dir: "${packageLocalPath}", glob: '', zipFile: "${archiveZipLocalPath}"
		}

		stage('Cleanup')
		{
			// Revert files checkout for package script (And optional unshelved CL)
			echo "Revert //..."
			def revertedFiles = p4.run('revert', '//...')
			echo GetListOfClientFile(revertedFiles)
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
