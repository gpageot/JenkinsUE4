#!groovy

// Gregory Pageot
// 2018-07-23

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
	// If true, only the engine will be built
	def engineOnly = ENGINE_ONLY.toBoolean()
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
				populateOption = forceClean(have: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true)
			}
			else
			{
				populateOption = syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true)
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

			// Generate Visual studio projects
			if(engineOnly)
			{
				bat """
					cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
					GenerateProjectFiles.bat -rocket -progress
					"""
			}
			else
			{
				bat """
					cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
					GenerateProjectFiles.bat -project=\"${projectLocalPath}\\${projectName}.uproject\" -game -rocket -progress
					"""
			}
		}

		// Unnecessary if we use p4publish and if '+w' P4 filetype flag is setup properly for all those files
		stage('Checkout')
		{
			echo "edit engine binaries"
			def editedFiles = p4.run('edit', 
				"${engineLocalPath}/Engine/Binaries/Win64/....exe".toString(),
				"${engineLocalPath}/Engine/Binaries/Win64/....dll".toString(),
				"${engineLocalPath}/Engine/Binaries/Win64/....pdb".toString(),
				"${engineLocalPath}/Engine/Binaries/Win64/....target".toString(),
				"${engineLocalPath}/Engine/Binaries/Win64/....modules".toString(),
				"${engineLocalPath}/Engine/Binaries/Win64/....version".toString(),
				"${engineLocalPath}/Engine/Plugins/.../Binaries/Win64/....dll".toString(),
				"${engineLocalPath}/Engine/Plugins/.../Binaries/Win64/....modules".toString()
				)

			echo GetListOfClientFile(editedFiles)

			if(engineOnly == false)
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
		}

		stage( 'Compile' )
		{
			// Compile the game
			// Same as Visual studio NMake
			if(engineOnly)
			{
				bat """
					cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
					Build.bat UE4Editor Win64 Development
					"""
			}
			else
			{
				bat """
					cd /D \"${engineLocalPath}\\Engine\\Build\\BatchFiles\"
					Build.bat ${projectName}Editor Win64 Development -Project=\"${projectLocalPath}\\${projectName}.uproject\"
					"""
			}
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
		slackSend color: 'good', message: "${buildFixed?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${engineOnly?'':projectName} ${optionFullRebuild?'rebuild ':''}${buildFixed?'fixed':'succeed'} (${env.BUILD_URL})"
	}
	catch (exception)
	{
		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def buildFirstFail = previousBuildSucceed
		slackSend color: 'bad', message: "${buildFirstFail?'@here ':''}${env.JOB_NAME} ${env.BUILD_NUMBER} ${engineOnly?"":projectName} ${optionFullRebuild?'rebuild ':'	'}${buildFirstFail?'failed':'still failing'} (${env.BUILD_URL})\n${buildFirstFail?GetChangelistsDesc():''}"
		throw exception
	}
}