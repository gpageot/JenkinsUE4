#!groovy

// Gregory Pageot
// 2024-02-10
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Compile the project with a custom engine (Downloaded from Epic's GitHub or perforce)
//
// Jenkins job parameter:
// ENGINE_LOCAL_PATH: String						Path to Jenkins local engine folder for the given perforce workspace
// PROJECT_LOCAL_PATH: String						Path to Jenkins local project folder for the given perforce workspace
// PROJECT_NAME: String								Project Name
// PROJECT_TARGET_NAME: String						[optional] Name of the game module, by default will use PROJECT_NAME
// BINARIES_ZIP_LOCAL_FOLDER: String				Folder path to the zip file to be updated
		// BINARIES_FILE_NAME: String						File name to the zip file to be updated
// ADDITIONAL_BUILDGRAPH_OPTION: String				Optional, additional BuildGraph option
// P4_WORKSPACE_NAME: String						Name of the perforce workspace use buy this job, need to be made in advance
// P4_WORKSPACE_NAME_FORPUBLISH: String				This a P4 workspace with a VIRTUAL p4 stream only including the file you want to submit (ie Binaries/)
// JENKINS_P4_CREDENTIAL: String					Add a credential with: Credentials > Jenkin (Global) > Global credentials > Add Credentials > Perforce Password Credential
// P4_UNICODE_ENCODING: String						If server if not set to support unicode, set it to "auto"
// P4_FORCE_SYNC: Boolean							If we force P4 sync, useful in case some file are left writable
// FULL_REBUILD: Boolean							If true, the perforce workspace will do a force clean
// SETUP_ENGINE: Boolean							If true, the engine setup batch will be called
// P4_LABEL_NAME: String							Name of the label added on succesful compilation
// P4_LABEL_DESC: String							Description of the label added on successful compilation
// TARGET_PLATFORM: String							[optional] List of targeted platform separated by semicolon
// SETUP_ENGINE_OPTION: String						[optional] List of option to add to the engine setup batch (useful to remove unused platform)
//													Here an example for a minimal "Win64 Editor" build
//													--prompt --exclude=osx32 --exclude=TVOS --exclude=Mac --exclude=mac-arm64 --exclude=WinRT --exclude=Linux --exclude=Linux32 --exclude=Linux64 --exclude=Unix --exclude=OpenVR --exclude=GoogleOboe --exclude=GooglePlay --exclude=GoogleGameSDK
//													You may need to adjust this if you have issue with some module not be part of the precompiled binaries ZIP file but part of the UnrealEditor.modules
//													You can replace "--prompt" by "--force" but "---prompt" seems to work on build machine

// TODO: Add to P4 tips: If you don't see the virtual stream you are creating, make sure you have the right to your main stream root folder


// If you encounter an issue with unrealEditor.modules not matching the dll in the zip files (for example UnrealEditor-MetalFormat)
// it may be cause by an old engine setup, so delete your Binaries/Win64 and re get from perforce, then re-run this pipeline (or use the option FULL_REBUILD)

// Need permission for:
// 
// org.jenkinsci.plugins.p4.groovy.P4Groovy
// org.jenkinsci.plugins.p4.changes.P4ChangeEntry
// staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods sprintf java.lang.Object java.lang.String java.lang.Object.

// WARNING:
// If the pipeline get stuck in "Installing prerequisites..." then you need to manually run the Setup.bat
// And then commented it out.
// This is due to windows UAC prompt
//
// Need to install .net "4.6.2 Developper pack"

// WARNING : UGS require the PDBCOPY.EXE tool to be installed or you will have an error
// Do not use the visual studio installer to get the SDK
//
// ERROR: Unable to find installation of PDBCOPY.EXE, which is required to strip symbols. This tool is included as part of the 'Windows Debugging Tools' component of the Windows 10 SDK (https://developer.microsoft.com/en-us/windows/downloads/windows-10-sdk).

import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

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
		if((status != 'ABORTED') && (status != 'NOT_BUILT'))
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
	def projectTargetName = projectName
	if(PROJECT_TARGET_NAME != "")
	{
		projectTargetName = PROJECT_TARGET_NAME
	}
	def binariesZipLocalFolder = BINARIES_ZIP_LOCAL_FOLDER
	def binariesZipFileName	= BINARIES_FILE_NAME
	def additionalBuildGraphOption = ADDITIONAL_BUILDGRAPH_OPTION

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
	// If true, the engine setup batch will be called
	def setupEngine = SETUP_ENGINE.toBoolean()

	def perforceLabelName = P4_LABEL_NAME
	def perforceLabelDescription = P4_LABEL_DESC
	def targetPlatforms = "Win64"
	def setupOptions = SETUP_ENGINE_OPTION
	if(TARGET_PLATFORM != "")
	{
		targetPlatforms = TARGET_PLATFORM
	}

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
				// If you encounter an issue of type "Can't clobber writable file", make sure the p4 workspace was created with CLOBBER=TRUE
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
			// Edit files necessary for GenerateProjectFiles batch
			echo "edit UnrealBuildTool.xml and UnrealBuildTool.exe.config"
			def editedFiles = p4.run('edit', 
				"${engineLocalPath}/Engine/Binaries/DotNET/UnrealBuildTool.xml".toString(),
				"${engineLocalPath}/Engine/Binaries/DotNET/UnrealBuildTool.exe.config".toString()
				)
			echo GetListOfClientFile(editedFiles)

			// We need to use double quote for batch path in case engine path contains space
			// /D				Change drive at same time as current folder

			if(setupEngine)
			{
				// Need '--force' option as we can't manage prompt
				// Note that this may freeze as the script ask to change filetype association which require admin rights
				// Note that this command and take a long time at it will download GitDependencies from internet
				bat """
					cd /D \"${engineLocalPath}"
					Setup.bat ${setupOptions}
					"""
			}
		}

		stage( 'Compile' )
		{
		// engine only version:
		//	bat """
		//		cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
		//		RunUAT.bat BuildGraph -Script=Engine/Build/Graph/Examples/BuildEditorAndTools.xml -Target="Submit To Perforce for UGS" -set:ArchiveStream=//streamsDepot/ue5-Binaries
		//		"""

			// For this to work, you need to make sure that your game is part of the UE5.sln generated by "Engine/Programs/" in the engine folder
			// To fix this, use perforce stream remapping to make sure the project is at the same level or under the engine
			// And change the "Default.uprojectdirs" to make sure your game is inside the UE5.sln generated by "GenerateProjectFiles.bat"
			// Note that we do not use the option "-p4 -submit" as those expect perforce environment varfiable to be set
			bat """
				cd /D \"${engineLocalPath}"
				Engine/Build/BatchFiles/RunUAT.bat BuildGraph -Script=Engine/Build/Graph/Examples/BuildEditorAndTools.xml -Target="Submit To Perforce for UGS" "${additionalBuildGraphOption}" -set:EditorTarget="${projectTargetName}Editor" -set:ArchiveStream=//streamsDepot/ue5-Binaries -set:TargetPlatforms="${targetPlatforms}"
				"""
		}

		stage( 'Move to binaries workspace' )
		{
			// As move command does not want to overwrite the read only file, go through multiple command
			// Use 'copy' in place of 'move' to keep file for debugging
			bat """
				cd /D \"${engineLocalPath}\\"
				del /f "${binariesZipLocalFolder}\\${binariesZipFileName}"
				IF NOT ERRORLEVEL 1 ECHO delete successful
				//UE5.3
				//copy /y "LocalBuilds\\ArchiveForUGS\\Perforce\\Unknown-${projectTargetName}Editor.zip" "${binariesZipLocalFolder}\\${binariesZipFileName}"
				//UE5.4
				copy /y "LocalBuilds\\ArchiveForUGS-Perforce\\Unknown-${projectTargetName}Editor.zip" "${binariesZipLocalFolder}\\${binariesZipFileName}"
				IF NOT ERRORLEVEL 1 ECHO move successful
				"""
		}

		stage( 'Submit' )
		{
			def changes = p4.run('changes', '-m1', '#have')
			def clNumber = changes[0]['change']
			//def clNumber = P4_CHANGELIST
			//echo "CL number: ${clNumber}"
			Integer clNumberInt = clNumber as Integer
			
			def clNumberAsDigit = sprintf( "CL %08d", clNumberInt )
			//echo "Formatted CL number: ${clNumberAsDigit}"
			
			// IMPORTANT: for UGS to work the P4 comment should start with "[CL 00000000]" 00000000 being the p4 version used to compile the binaries
			// "purge" limit number of revision kept for the zip file
			// "delete" Propagate deletes
			p4publish credential: perforceCredentialInJenkins,
				publish: submit(delete: true, description: "[${clNumberAsDigit}] ${env.JOB_NAME} ${env.BUILD_NUMBER} ${optionFullRebuild?'rebuild':''}".toString(), onlyOnSuccess: false, purge: '32', reopen: false),
				workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceNameForPublish, pinHost: false)
		}

		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def previousBuildFailed = previousBuildSucceed == false
		def buildFixed = previousBuildFailed
		slackSend color: 'good', message: "${buildFixed?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${projectName} ${optionFullRebuild?'rebuild ':''}${buildFixed?'fixed':'succeed'} (${env.BUILD_URL})"
	}
	catch (FlowInterruptedException e)
	{
		echo "Job was interupt, ignoring exception"
	}
	catch (Exception e)
	{
		// TODO : cleanup possible remaining changelist in binaries workspace
		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def buildFirstFail = previousBuildSucceed
		slackSend color: 'bad', message: "${buildFirstFail?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${projectName} ${optionFullRebuild?'rebuild ':'	'}${buildFirstFail?'failed':'still failing'} (${env.BUILD_URL})\n${buildFirstFail?GetChangelistsDesc():''}"
		throw exception
	}
}