#!groovy

// Gregory Pageot
// 2018-10-01

// Need permission for:
//
// method org.jenkinsci.plugins.p4.groovy.P4Groovy fetch java.lang.String java.lang.String
// method org.jenkinsci.plugins.p4.groovy.P4Groovy save java.lang.String java.util.Map
def GetLastestSubmittedChangelistOfUser(p4cmdobj, UserName, ServerPath)
{
	def changes = p4cmdobj.run('changes', '-m', '1', '-s', 'submitted', '-u', UserName.toString(), "${ServerPath}".toString())

	if(changes.length < 1)
	{
		return "0"
	}
	return changes[0]['change']
}

node
{
	def majorVersion = LABEL_VERSION_ID
	def perforceEpicBranchServerPath = P4_SERVER_PATH

	def perforceUserName = P4_USER_NAME
	def perforceWorkspaceName = P4_WORKSPACE_NAME
	def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
	def perforceUnicodeMode = P4_UNICODE_ENCODING

	def p4 = p4 credential: perforceCredentialInJenkins, workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)

	def changelistNumber = ''
	stage('get and label')
	{
		def labelView = "${perforceEpicBranchServerPath}/..."
		changelistNumber = GetLastestSubmittedChangelistOfUser(p4, perforceUserName, labelView)
		echo ("Latest changelist submitted by user '" + perforceUserName + "' is: " + changelistNumber)

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

		for(def item : changes) {
				echo ("Item: " + item)
				for (String key : item.keySet()) {
				value = item.get(key)
				echo ("Key: " + key + " Value: " + value)
			} 
		}
	}
}