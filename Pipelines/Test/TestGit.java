#!groovy

// Gregory Pageot
// 2018-09-10

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

	stage( 'Clean up git directory' )
	{
		// Remove all local files (but keep directory)
		def destinationDirPath = new File(gitFolderPath)
		FileUtils.forceMkdir(destinationDirPath)
		FileUtils.cleanDirectory(destinationDirPath)
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