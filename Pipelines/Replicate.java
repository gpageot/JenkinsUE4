#!groovy

// Gregory Pageot
// 2018-09-10

def cond_stage(name, execute, block) {
	return stage(name, execute ? block : {echo "skipped stage $name"})
}

def GetLastestSubmittedChangelistOfUser(p4cmdobj, UserName, ServerPath) {
	def changes = p4cmdobj.run('changes', '-m', '1', '-s', 'submitted', '-u', UserName.toString(), "${ServerPath}".toString())
	/*for(def item : changes) {
			echo ("Item: " + item)
			for (String key : item.keySet()) {
			value = item.get(key)
			echo ("Key: " + key + " Value: " + value)
		} 
	}
	echo ("Latest changelist submitted by admin user is: " + changes[0]['change'])*/
	// TODO: manage case where no changelist present
	return changes[0]['change']
}

// Need permission for:
//
// staticMethod java.io.File listRoots
// java.io.File getAbsolutePath
// java.io.File getFreeSpace
def CheckDiskSpace(driveLetter, limitInGB) {
	def result = false
	File.listRoots().each
	{
		if (!"".equals(driveLetter) && !driveLetter.equals(it.getAbsolutePath()))
		{
			// skip to next element
			return
		}
		freeSpaceInGB = it.getFreeSpace() / 1024 / 1024 / 1024
		if (freeSpaceInGB >= limitInGB)
		{
			result = true
			echo "Enough disk space: ${freeSpaceInGB}"
		}
		else
		{
			echo "Not enough disk space: ${freeSpaceInGB}"
		}
	}
	return result
}

// Need permission for:
//
// method java.io.File mkdirs
// new java.io.File java.lang.String
// staticMethod org.codehaus.groovy.runtime.DefaultGroovyMethods deleteDir java.io.File
// method org.jenkinsci.plugins.p4.groovy.P4Groovy run java.lang.String java.lang.String[]
// method org.jenkinsci.plugins.p4.groovy.P4Groovy fetch java.lang.String java.lang.String
// method org.jenkinsci.plugins.p4.groovy.P4Groovy save java.lang.String java.util.Map
node
{
	try{
		def majorVersion = LABEL_VERSION_ID
		def gitFolderPath = GITHUB_LOCAL_PATH
		def gitServerURL = GITHUB_EPIC_SERVER_URL
		def gitCredential = GITHUB_CREDENTIAL
		def gitExePath = 'C:\\Program Files\\Git\\bin\\git.exe'

		def perforceEpicBranchFolderPath = P4_EPICBRANCH_LOCAL_PATH
		def perforceEpicBranchServerPath = P4_EPICBRANCH_SERVER_PATH
		def perforceUserName = P4_USER_NAME
		def perforceWorkspaceName = P4_WORKSPACE_NAME
		def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
		def perforceUnicodeMode = P4_UNICODE_ENCODING
		def debugSkipGitGet = DEBUG_SKIP_GIT_GET.toBoolean()
		def debugSkipDeleteAndCopy = DEBUG_SKIP_COPY.toBoolean()
		def debugSkipReconcile = DEBUG_SKIP_RECONCILE.toBoolean()
		def debugSkipSubmit = DEBUG_SKIP_SUBMIT.toBoolean()

		stage('Check Disk Space')
		{
			// Here we should test for the drive where Git is downloaded and where it is replicated to perforce
			// TODO: Automatic driver letter detection based on local path
			def result = CheckDiskSpace("D:\\", 50.0)
			if(result == false)
			{
				error "Not enough disk space"
			}
		}

		cond_stage( 'Clean up git directory', !debugSkipGitGet)
		{
			// Remove all local files (but keep directory)
			def destinationDirPath = new File(gitFolderPath)
			destinationDirPath.deleteDir();
			destinationDirPath.mkdirs();
		}

		cond_stage('Get Epic UE4 (Git)', !debugSkipGitGet)
		{
			def gitBranchName = "*/${majorVersion}"
			
			// 19MB/s - 24MB/s
			bat """\"${gitExePath}\" clone --branch ${majorVersion} -v ${gitServerURL} ${gitFolderPath}"""
			/*
			// 200KB/s - 500KB/s
			// We need to use the checkout step in order to specify the path where to clone the depot
			checkout changelog: false, poll: false,
				scm: [$class: 'GitSCM', branches: [[name: gitBranchName]],
				doGenerateSubmoduleConfigurations: false,
				extensions: [
					[$class: 'CheckoutOption', timeout: 30],
					[$class: 'CloneOption', timeout: 30],
					[$class: 'RelativeTargetDirectory', relativeTargetDir: gitFolderPath]],
				submoduleCfg: [],
				userRemoteConfigs: [[
					credentialsId: gitCredential,
					url: gitServerURL]]]
			*/
		}

		// We run the setup.bat before the copy in order that the copy process filter files/folders out
		// Note that we could run it after the copy in order to avoid copying data downloaded by this step(around 5.5GB as of UE4.20)
		stage('Run Epic UE4 setup.bat')
		{
			// Directly calling GitDependencies as we don't want to install prerequisites(which will get the jenkins job stuck)
			// We are specifying a lot of platfrom to exclude in order to limit the amount of files to copy around
			// TODO: dynamicly generate the platofrm list
			bat "${gitFolderPath}/Engine/Binaries/DotNET/GitDependencies.exe -exclude=Android -exclude=HTML5 -exclude=WinRT -exclude=IOS -exclude=Mac -exclude=osx32 -exclude=osx64 -exclude=TVOS"
			//-exclude=Linux -exclude=Linux32 -exclude=IOS
		}

		cond_stage( 'Update local Epic branch', !debugSkipDeleteAndCopy )
		{
			// Update local perforce workspace for Epic branch as we want to reconcile with latest version
			checkout perforce(
					credential: perforceCredentialInJenkins,
					populate: syncOnly(force: false, have: true, modtime: true, parallel: [enable: false, minbytes: '1024', minfiles: '1', threads: '4'], pin: '', quiet: true, revert: true),
					workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false))
		}

		cond_stage( 'Delete local files', !debugSkipDeleteAndCopy )
		{
			// Remove all local files (but keep directory)
			def destinationDirPath = new File(perforceEpicBranchFolderPath)
			//FileUtils.cleanDirectory(destinationDirPath) // doesn't work with readonly files
			destinationDirPath.deleteDir();
			destinationDirPath.mkdirs();
		}

		cond_stage( 'Copy', !debugSkipDeleteAndCopy )
		{
			def excludeFilePath = ".\\ExcludeList.txt"
			writeFile(file: excludeFilePath, text: """
.gitignore
.gitattributes
\\.git\\
\\Documentation\\""")
//			\\Engine\\Documentation\\""") not tested
			// /s		Copies directories and subdirectories, unless they are empty. If you omit /s, xcopy works within a single directory.
			// /r		Copies read-only files.
			// /h		Copies files with hidden and system file attributes. By default, xcopy does not copy hidden or system files
			// /exclude	Specifies a list of files. At least one file must be specified. Each file will contain search strings with each string on a separate line in the file.
			// /y		Suppresses prompting to confirm that you want to overwrite an existing destination file.
			bat """xcopy "${gitFolderPath}" "${perforceEpicBranchFolderPath}" /s /r /h /exclude:${excludeFilePath} /y """
		}

		def p4 = p4 credential: perforceCredentialInJenkins, workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)

		cond_stage( 'Create perforce changelist', !debugSkipReconcile )
		{
			// -e		Edit files: Find files in the client workspace that have been modified outside of Perforce, and open them for edit.
			// -a		Add files: Find files in the client workspace that are not under Perforce control and open them for add.
			// -d		Delete files: Find files missing from the client workspace, but present on the server; open these files for delete, but only if these files are in the user's have list.
			p4.run('reconcile', '-e', '-a', '-d', "${perforceEpicBranchServerPath}/... ".toString())
			//p4.run('reconcile', '-e', '-a', '-d', "\"${perforceEpicBranchsFolderPath}\\...\" ".toString())
		}

		cond_stage( 'Submit to perforce', !debugSkipSubmit )
		{
			// -d		Immediately submit the default changelist with the description supplied on the command line, and bypass the interactive form. This option is useful when scripting, but does not allow for jobs to be added, nor for the default changelist to be modified.
			p4.run('submit', "-d \"[ue4] ${env.JOB_NAME} ${env.BUILD_NUMBER} Version: ${majorVersion}\"".toString())
		}
		
		cond_stage( 'Add perforce label', !debugSkipSubmit )
		{
			def labelView = "${perforceEpicBranchServerPath}/..."
			changelistNumber = GetLastestSubmittedChangelistOfUser(p4, perforceUserName, labelView)
			echo ("Latest changelist submitted by admin user is: " + changelistNumber)

			def labelName = "EPIC_UE${majorVersion}"
			def labelOwner = "${perforceUserName}"
			def labelDesc = "Label automaticly setup by jenkins for Epic UE4 version ${majorVersion}"
			def labelRevision = "${changelistNumber}"

			def label = p4.fetch('label', labelName)
			def owner = label.get('Owner')
			label.put('Owner', labelOwner.toString())
			label.put('Description', labelDesc.toString())
			label.put('Revision', "@${labelRevision}")
			label.put('View', "@${labelView}")
			def changes = p4.save('label', label)
		}

		// TODO : clean up Git directory to save disk space (only if debugSkipGitGet is false)
		// TODO : clean up perforce directory to save disk space (only if debugSkipSubmit is false)

		slackSend color: 'good', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} succeed (${env.BUILD_URL})"
	}
	catch (exception)
	{
		slackSend color: 'bad', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} failed (${env.BUILD_URL})"
		throw exception
	}
}