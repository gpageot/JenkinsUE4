#!groovy

// Gregory Pageot
// 2020-06-09
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Compile the project with a engine downloaded from Epic Game Launcher
//
// Jenkins job parameter:
// ENGINE_LOCAL_PATH: String
// PROJECT_LOCAL_PATH: String
// PROJECT_NAME: String
// P4_WORKSPACE_NAME: String
// P4_WORKSPACE_NAME_FORPUBLISH: String				// This a P4 workspace with a VIRTUAL p4 stream only including the file you want to submit (ie Binaries/)
// JENKINS_P4_CREDENTIAL: String
// P4_UNICODE_ENCODING: String
// P4_FORCE_SYNC: Boolean
// FULL_REBUILD: Boolean
// P4_LABEL_NAME: String
// P4_LABEL_DESC: String


// TODO: Add to P4 tips: If you don7t see the virtual stream you are creating, make sure you have the right to yout mail steam root folder


// Need permission for:
// 
// org.jenkinsci.plugins.p4.groovy.P4Groovy
// org.jenkinsci.plugins.p4.changes.P4ChangeEntry

// WARNING:
// If the pipeline get stuck in "Installing prerequisites..." then you need to manually run the Setup.bat
// And then commented it out.
// This is due to windows UAC prompt
//
// Need to install .net "4.6.2 Developper pack"


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
	// Path to Jenkins local engine folder for the given perforce workspace
	def engineLocalPath = ENGINE_LOCAL_PATH
	// Path to Jenkins local project folder for the given perforce workspace
	def projectLocalPath = PROJECT_LOCAL_PATH
	// Project Name
	def projectName = PROJECT_NAME
	// Name of the perforce workspace use by this pipeline
	def perforceWorkspaceName = P4_WORKSPACE_NAME
	// Name of the perforce workspace use by this pipeline to submit files to perforce
	def perforceWorkspaceNameForPublish = P4_WORKSPACE_NAME_FORPUBLISH
	// Jenkins credential ID to use the given perforce workspace
	def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
	// Encoding for the given perforce workspace
	def perforceUnicodeMode = P4_UNICODE_ENCODING
	// If we force P4 sync, useful in case some file are left writable
	def perforceForceSync = P4_FORCE_SYNC.toBoolean()
	// If true, the perforce workspace will do a force clean
	def optionFullRebuild = FULL_REBUILD.toBoolean()

	def perforceLabelName = P4_LABEL_NAME
	def perforceLabelDescription = P4_LABEL_DESC

	try
	{
		stage('Get perforce')
		{
			def populateOption
			if(optionFullRebuild)
			{
				// Currently failing with "ERROR: P4: Task Exception: java.io.IOException: Unable to delete directory [Perforce_Workspace_Folder]."
				populateOption = forceClean(have: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true)
			}
			else
			{
				populateOption = syncOnly(force: perforceForceSync, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true)
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
			// We need to use double quote for batch path in case engine path contains space
			// /D				Change drive at same time as current folder		

			// Generate Visual studio projects ( Epic launcher version does not have GenerateProjectFiles.bat)
			bat """
				cd /D \"${engineLocalPath}\\Engine\\Binaries\\DotNET\\"
				UnrealBuildTool.exe  -projectfiles -project=\"${projectLocalPath}\\${projectName}.uproject\" -game -rocket -progress
			"""
		}

		// Unnecessary if we use p4publish and if '+w' P4 filetype flag is setup properly for all those files
		stage('Checkout')
		{
			echo "edit project binaries"
			editedFiles = p4.run('edit', 
				"${projectLocalPath}/${projectName}.uproject".toString(),
				"${projectLocalPath}/Binaries/Win64/....exe".toString(),
				"${projectLocalPath}/Binaries/Win64/....dll".toString(),
				"${projectLocalPath}/Binaries/Win64/....pdb".toString(),
				"${projectLocalPath}/Binaries/Win64/....target".toString(),
				"${projectLocalPath}/Binaries/Win64/....modules".toString(),
				"${projectLocalPath}/Binaries/Win64/....version".toString(),
				"${projectLocalPath}/Plugins/.../Binaries/Win64/....dll".toString(),
				"${projectLocalPath}/Plugins/.../Binaries/Win64/....pdb".toString(),
				"${projectLocalPath}/Plugins/.../Binaries/Win64/....modules".toString()
				)

			echo GetListOfClientFile(editedFiles)
		}

		stage( 'Compile' )
		{
			// Compile the game
			// Same as Visual studio NMake
			// Rocket build
			// https://blog.mi.hdm-stuttgart.de/index.php/2017/02/11/uat-automation/
			bat """
				cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
				Build.bat ${projectName}Editor Win64 Development -Project=\"${projectLocalPath}\\${projectName}.uproject\"
				"""
		}

		stage('Submit')
		{
			// Revert while keeping files in order that the 2nd workspace submit those files
			echo "Revert -k //..."
			def revertedFiles = p4.run('revert', '-k', '//...')
			echo GetListOfClientFile(revertedFiles)

			// Problem p4 publish do a "revert -k", "sync -k", "reconcile -e -a -f"
			// which will result in adding saved/intermediates&co folders ... So we need to use a different P4 workspace that match the same location but limited to folder we want to submit
			p4publish credential: perforceCredentialInJenkins,
				publish: submit(delete: false, description: "[ue4] ${env.JOB_NAME} ${env.BUILD_NUMBER} ${optionFullRebuild?'rebuild':''}".toString(), onlyOnSuccess: false, purge: '', reopen: false),
				workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceNameForPublish, pinHost: false)

			// Updating the Perforce Label.
			// Note that here we use the P4 workspace used to compile, so the Label will contains all folders that are maps to that workspace
			// as the P4 plugin doesn't current allow to specify the mapping in the command.
			p4tag credential: perforceCredentialInJenkins,
				rawLabelDesc: perforceLabelDescription,
				rawLabelName: perforceLabelName
		}

		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def previousBuildFailed = previousBuildSucceed == false
		def buildFixed = previousBuildFailed
		slackSend color: 'good', message: "${buildFixed?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${projectName} ${optionFullRebuild?'rebuild ':''}${buildFixed?'fixed':'succeed'} (${env.BUILD_URL})"
	}
	catch (exception)
	{
		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def buildFirstFail = previousBuildSucceed
		slackSend color: 'bad', message: "${buildFirstFail?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${projectName} ${optionFullRebuild?'rebuild ':'	'}${buildFirstFail?'failed':'still failing'} (${env.BUILD_URL})\n${buildFirstFail?GetChangelistsDesc():''}"
		throw exception
	}
}