#!groovy

// Gregory Pageot
// 2018-07-23

// Using p4publish allow to avoid the 5minute command timeout issue
// Demerit being that it requires to have 1 available workspace license (as a temporary one will be created)

// Will create a new changelist. Return its CL number
// WARNING: this will include ALL files in the newly created CL, including files in pending changelists(TODO : only include files in Default changelist)
def CreateCLFromDefault(p4, String p4WorkspaceName, String description)
{
	def job = p4.run('change', '-o')

	for(def item : job)
	{
		// Change description field
		def desc = item.get('Description')
		//echo desc
		desc = description
		//echo desc
		item.put('Description', desc)

		p4.save('change', item)
	}

	// List pending changelist on current P4 workspace
	def changelistnumber = -1
	def jobAllCL = p4.run('changes', '-s', 'pending', '-c', p4WorkspaceName)
	for(def item : jobAllCL)
	{
		changelistnumber = item.get("change")
		break // First line is the last create changelist
	}
	return changelistnumber
}

def CreateCLFromDefaultFilterered(p4, String p4WorkspaceName, String description, String[] paths)
{
	def clNumber = CreateCLFromDefault(p4,  p4WorkspaceName, description)

	// move all files back to default CL
	p4.run('reopen', '-c', 'default', '//...')

	paths.each {
		p4.run('reopen', '-c', clNumber, it)
	}

	return clNumber
}

@NonCPS
def readDir(String folder)
{
	def dirslist = []
	new File(folder).eachDir()
	{ dir ->
		if (!dir.getName().startsWith('.')) {
			dirslist.add(dir.getName())
		}
	}
	return dirslist
}

def p4AddFilesInFolder(p4, String folder)
{
	def addedFiles = p4.run('add', "${folder}\\...".toString())

	String addFilesReport = ""
	for(def item : addedFiles) {
		for (String key : item.keySet()) {
			if(key == 'clientFile') {
				value = item.get(key)
				addFilesReport += value + "\n"
			}
			// //value = item.get(key)
			// //println ("Key: " + key + " Value: " + value)
		}
	}
	echo addFilesReport
}

def p4AddBinariesForPlugins(p4, String PluginsFolder)
{
	String[] pluginList = readDir(PluginsFolder)
	for(def pluginName : pluginList) {
		echo "Adding binaries for plugin: ${pluginName}"
		p4AddFilesInFolder(p4, "${PluginsFolder}\\${pluginName}\\Binaries".toString())
	}
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
	// TODO : comment
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
	// TODO : comment
	def optionFullRebuild = FULL_REBUILD.toBoolean()
	try{

		stage('Get perforce')
		{
			if(optionFullRebuild)
			{
				// Currently failing with "ERROR: P4: Task Exception: java.io.IOException: Unable to delete directory [Perforce_Workspace_Folder]."
				checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: forceClean(have: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)
					)
			}
			else
			{
				checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)
					)
			}

			String checkoutReport = ""
			echo "currentBuild.changeSets size ${currentBuild.changeSets.size()}"
			for(def item : currentBuild.changeSets) {
				checkoutReport += item
			}
			echo checkoutReport

			String checkoutReportAuthors = ""
			Set authors = currentBuild.changeSets.collect({ it.items.collect { it.author.toString() } })
			Set uniqueAuthors = authors.unique()
			for(def item : uniqueAuthors) {
				checkoutReportAuthors += item
			}
			echo checkoutReportAuthors
		}

		def p4 = p4 credential: perforceCredentialInJenkins, workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)

		stage( 'Prepare' )
		{
			// Edit files necessary for GenerateProjectFiles batch
			def editedFiles = p4.run('edit', 
				"${engineLocalPath}/Engine/Binaries/DotNET/UnrealBuildTool.xml".toString(),
				"${engineLocalPath}/Engine/Binaries/DotNET/UnrealBuildTool.exe.config".toString()
				)
			String editFilesReport = ""
			for(def item : editedFiles) {
				for (String key : item.keySet()) {
					if(key == 'clientFile') {
						value = item.get(key)
						editFilesReport += value + "\n"
					}
				}
			}
			echo editFilesReport

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
			String editFilesReport = ""
			for(def item : editedFiles) {
				for (String key : item.keySet()) {
					if(key == 'clientFile') {
						value = item.get(key)
						editFilesReport += value + "\n"
					}
				}
			}
			echo editFilesReport

			if(engineOnly == false)
			{
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
				editFilesReport = ""
				for(def item : editedFiles) {
					for (String key : item.keySet()) {
						if(key == 'clientFile') {
							value = item.get(key)
							editFilesReport += value + "\n"
						}
					}
				}
				echo editFilesReport
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
		/*
		// unnecessary since if we use p4publish
		stage('Add new files')
		{
			// Add new binaries for new plugins, or after engine merge
			// WARNING: dll/pdb will not be removed for plugins or engine modules remove!
			p4AddFilesInFolder(p4, "${engineLocalPath}\\Engine\\Binaries\\Win64")

			p4AddBinariesForPlugins(p4, "${engineLocalPath}\\Engine\\Plugins")

			if(engineOnly == false)
			{
				p4AddFilesInFolder(p4, "${projectLocalPath}\\Binaries\\Win64")

				// EXP files should not be added to source control
				echo "Revert EXP file"
				def revertedFiles = p4.run('revert', "${projectLocalPath}\\Binaries\\Win64\\*.exp".toString())
				String revertFilesReport = ""
				for(def item : revertedFiles) {
					for (String key : item.keySet()) {
						if(key == 'clientFile') {
							value = item.get(key)
							revertFilesReport += value + "\n"
						}
					}
				}
				echo revertFilesReport

				p4AddBinariesForPlugins(p4, "${projectLocalPath}\\Plugins")
			}
		}*/

		stage('Submit')
		{
			// Revert while keeping files in order that the 2nd workspace submit those files
			echo "Revert -k"
			def revertedFiles = p4.run('revert', '-k', '//...')
			String revertFilesReport = ""
			for(def item : revertedFiles) {
				for (String key : item.keySet()) {
					if(key == 'clientFile') {
						value = item.get(key)
						revertFilesReport += value + "\n"
					}
				}
			}
			echo revertFilesReport

			// Problem p4 publish do a "revert -k", "sync -k", "reconcile -e -a -f"
			// which  will result in adding saved/intermediates&co folders ... So we need to use a different P4 workspace that match the same location but limited to folder we want to submit
			p4publish credential: perforceCredentialInJenkins,
				publish: submit(delete: false, description: "[ue4] ${env.JOB_NAME} ${env.BUILD_NUMBER} ${optionFullRebuild?'rebuild':''}".toString(), onlyOnSuccess: false, purge: '', reopen: false),
				workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceNameForPublish, pinHost: false)
			/*
			// Start by reverting unchanged files
			//p4.run('revert', '-a') // workspace should be setup to revert unchanged
			
			if(optionFullRebuild || engineOnly) // for engine only we also split in multiple CL
			{
				// Start by submitting all files except PDB in a single changelist
				String[] subpaths = [
					"${engineLocalPath}/....dll",
					"${engineLocalPath}/....exe",
					"${engineLocalPath}/....target",
					"${engineLocalPath}/....modules",
					"${engineLocalPath}/....version",
					"${engineLocalPath}/....config",
					"${projectLocalPath}/....dll",
					"${projectLocalPath}/....exe",
					"${projectLocalPath}/....target",
					"${projectLocalPath}/....modules",
					"${projectLocalPath}/....version",
					"${projectLocalPath}/....config"
					]

				def value = CreateCLFromDefaultFilterered(p4, perforceWorkspaceName, "[ue4] ${env.JOB_NAME} ${env.BUILD_NUMBER} ${optionFullRebuild?'rebuild':''}".toString(), subpaths)
				println ("Submit CL: " + value)
				//p4.run('submit', '-c', value, '--parallel=threads=8,batch=4')
				p4.run('submit', '-c', value)
			}
			else
			{
				//p4.run('submit', '-d', "[ue4] ${projectName}: ${env.JOB_NAME} ${env.BUILD_NUMBER}".toString(), '--parallel=threads=8,batch=4', '//...')
				p4.run('submit', '-d', "[ue4] ${projectName}: ${env.JOB_NAME} ${env.BUILD_NUMBER} ${optionFullRebuild?'rebuild':''}".toString(), '//...')
			}*/
		}

		/*
		// unnecessary since we use p4publish
		stage('Submit (PDB)')
		{
			if(optionFullRebuild || engineOnly) // for engine only we also split in multiple CL
			{
				//echo "Submit PDB (parallel)"
				//p4.run('submit', '-d', "[ue4] ${env.JOB_NAME} ${env.BUILD_NUMBER} (PDB)".toString(), '--parallel=threads=8,batch=4', '//...')
				// Failed with "Parallel file transfer must be enabled using net.parallel.max"
				
				
				// For full rebuild, in order to avoid the "5 minutes command abort" issue, we split in multiple changelist
				String[] subpaths = [
					"${engineLocalPath}/Engine/Binaries/Win64/Android/...",
					"${engineLocalPath}/Engine/Binaries/Win64/HTML5/...",
					"${engineLocalPath}/Engine/Binaries/Win64/IOS/...",
					"${engineLocalPath}/Engine/Binaries/Win64/Lumin/...",
					"${engineLocalPath}/Engine/Binaries/Win64/ShaderCompileWorker*.pdb",
					"${engineLocalPath}/Engine/Binaries/Win64/UnrealHeaderTool*.pdb",
					"${engineLocalPath}/Engine/Binaries/...",
					"${engineLocalPath}/Engine/Plugins/...",
					"${engineLocalPath}/Engine/...",
					"${projectLocalPath}/..."
					]

				subpaths.each {
					// Adding some output in order to track which reconcile need to be split further in order to avoid abort error.
					echo "Launching submit on ${it}"

					// -d		Immediately submit the default changelist with the description supplied on the command line, and bypass the interactive form. This option is useful when scripting, but does not allow for jobs to be added, nor for the default changelist to be modified.
					p4.run('submit', '-d', "[ue4] ${env.JOB_NAME} ${env.BUILD_NUMBER} ${it} ${optionFullRebuild?'rebuild':''}(PDB)".toString(), it)
				}
			}
		}*/

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