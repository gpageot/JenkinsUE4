#!groovy

// Gregory Pageot
// 2018-09-10

// Need permission for:
//
// new java.io.File java.lang.String
// deleteDir java.io.File
// java.io.File mkdirs
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
		destinationDirPath.deleteDir();
		destinationDirPath.mkdirs();
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