#!groovy

// Gregory Pageot
// 2018-09-10
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Test connection to Epic Git repository using your github credential
//
// Jenkins job parameter:
// GITHUB_BRANCH_NAME: String			Which engine version you are using, ex: "4.24"
// GITHUB_LOCAL_PATH: String			Local path where to pull git to, ex: "D:\JenkinsUE4\EpicGit"
// GITHUB_EPIC_SERVER_URL: String		Epic github URL for UnrealEngine, ex: "https://github.com/EpicGames/UnrealEngine.git"
// GITHUB_CREDENTIAL: String			Add a credential with: Credentials > Jenkin (Global) > Global credentials > Add Credentials > User name with password
// CLEAN_BEFORE_GET: Boolean			If true, the local folder will be erase first
//
// WARNING:
// Git.exe need to be install in the system running this script

import org.apache.commons.io.FileUtils

// Need permission for:
//
// new java.io.File java.lang.String
// org.apache.commons.io.FileUtils cleanDirectory java.io.File
// org.apache.commons.io.FileUtils forceMkdir java.io.File

node
{
	def epicBranchName = GITHUB_BRANCH_NAME
	def gitFolderPath = GITHUB_LOCAL_PATH
	def gitServerURL = GITHUB_EPIC_SERVER_URL
	def gitCredential = GITHUB_CREDENTIAL
	def optionCleanBeforeGet = CLEAN_BEFORE_GET.toBoolean()

	stage( 'Clean up git directory' )
	{
		if(optionCleanBeforeGet == false)
		{
			// Remove all local files (but keep directory)
			def destinationDirPath = new File(gitFolderPath)
			FileUtils.forceMkdir(destinationDirPath)
			FileUtils.cleanDirectory(destinationDirPath)
		}
	}

	stage( 'Git test' )
	{
		// We need to use the checkout step in order to specify the path where to clone the depot
		checkout changelog: false, poll: false,
			scm: [$class: 'GitSCM', branches: [[name: epicBranchName]],
			doGenerateSubmoduleConfigurations: false,
			extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: gitFolderPath]],
			submoduleCfg: [],
			userRemoteConfigs: [[
				credentialsId: gitCredential,
				url: gitServerURL]]]
	}
}