#!groovy

// Gregory Pageot
// 2020-10-12
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Build lighting for given map
// WARNING: Building lighting require a Graphic card with DX11 support an "DX11 end user runtimes" installed
// WARNING: Building lighting require to have jenkins started as user, not as service
//
// Jenkins job parameter:
// ENGINE_LOCAL_PATH: String
// PROJECT_LOCAL_PATH: String
// PROJECT_NAME: String
// P4_WORKSPACE_NAME: String
// JENKINS_P4_CREDENTIAL: String
// P4_UNICODE_ENCODING: String
//
// MAP_NAME: String
// MAP_FOLDER_TOCHECKOUT: String
//
// Precondition:
// Add a "Perforce Password credential" with perforce user&password
//
// Non standard plugin required:
// P4
// Slack Notification	(optional)
//
// Approval list
// method org.jenkinsci.plugins.p4.groovy.P4Groovy run java.lang.String java.lang.String[]

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
		
		
		def mapName = MAP_NAME
		def mapFolderCheckout = MAP_FOLDER_TOCHECKOUT
		
		stage('Get perforce')
		{
			// Set quiet to false in order to have output
			def populateOption = syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: false, revert: true)

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
			echo "edit ${projectLocalPath}/${mapFolderCheckout}"
			def editedFiles = p4.run('edit',
				"${projectLocalPath}/Content/${mapFolderCheckout}/...".toString()
				)
			echo GetListOfClientFile(editedFiles)
		}

		stage( 'Command' )
		{
			// Run the command
			// Use UE4Editor-Cmd.exe in order that the command return when build is finished
			// Run the command
			def result = bat """
				cd /D \"${engineLocalPath}\\Engine\\Binaries\\Win64\"
				UE4Editor-Cmd.exe \"${projectLocalPath}\\${projectName}.uproject\" ${mapName} -ExecCmds=\"BUILDPATHS,OBJ SAVEPACKAGE PACKAGE=/Game/${mapFolderCheckout}/${mapName} FILE=${projectLocalPath}/Content/${mapFolderCheckout}/${mapName}.umap SILENT=true AUTOSAVING=false KEEPDIRTY=false,QUIT_EDITOR" UseSCC=false -unattended
				"""
			echo "Build command result:"
			echo result
		}

		stage('Submit')
		{
			echo "Submit files"
			// To avoid sumitting unchanged files, make sure the P4 workspace is configured with the corresponding option
			def submittedFiles = p4.run('submit', '-d', '[jenkins] Navmesh build')
			echo GetListOfClientFile(submittedFiles)
		}
		
		stage('Cleanup')
		{
			echo "Looking for changelist"
			//String format = "\"change -d %change%\""
			//2020-10-12: It looks like we can't use g-opts ???
			//def changelistDeleteCmds = p4.run('changes', '-Ztag', '-F', format.toString(), '-s', 'pending', '-c', perforceWorkspaceName.toString())

			
			// List changelists.
			def changelistDeleteCmds = p4.run('changes', '-s', 'pending', '-c', perforceWorkspaceName.toString())
			
			def changlistNumberList = []
			for(def item : changelistDeleteCmds)
			{
				for (String key : item.keySet())
				{
					def value = item.get(key)
					if(key == "change")
					{
						echo "Found changelist ${value}"
						changlistNumberList.add(value)
					}
				}
			}

			// Delete changelists.
			for(def item : changlistNumberList)
			{
				echo "Deleting changelist ${item}"
				p4.run('change', '-d', item.toString())
			}
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
