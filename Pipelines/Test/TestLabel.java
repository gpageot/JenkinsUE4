#!groovy

// Gregory Pageot
// 2018-10-01
//
// Jenkins job parameter:
// LABEL_NAME: String					Name of the label
// P4_SERVER_PATH: String				Perforce server to tag with the label
// P4_OWNER_USER_NAME					Name of the owner of that label
// P4_WORKSPACE_NAME: String			Name of the perforce workspace use buy this job, need to be made in advance
// JENKINS_P4_CREDENTIAL: String		Add a credential with: Credentials > Jenkin (Global) > Global credentials > Add Credentials > Perforce Password Credential
// P4_UNICODE_ENCODING: String			If server if not set to support unicode, set it to "auto"
//
// Need permission for(see Manage jenkins > In-process Script Approval):
//
// method org.jenkinsci.plugins.p4.groovy.P4Groovy run java.lang.String java.lang.String[]
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
	try{
		def labelname = LABEL_NAME
		def serverPath = P4_SERVER_PATH

		def perforceUserName = P4_OWNER_USER_NAME
		def perforceWorkspaceName = P4_WORKSPACE_NAME
		def perforceCredentialInJenkins = JENKINS_P4_CREDENTIAL
		def perforceUnicodeMode = P4_UNICODE_ENCODING

		def p4 = p4 credential: perforceCredentialInJenkins, workspace: staticSpec(charset: perforceUnicodeMode, name: perforceWorkspaceName, pinHost: false)

		def changelistNumber = ''
		stage('get and label')
		{
			def labelView = "${serverPath}/..."
			changelistNumber = GetLastestSubmittedChangelistOfUser(p4, perforceUserName, labelView)
			echo ("Latest changelist submitted by user '" + perforceUserName + "' is: " + changelistNumber)

			def labelName = "${labelname}"
			def labelOwner = "${perforceUserName}"
			def labelDesc = "Label automaticly setup by jenkins for version ${labelname}"
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

		slackSend color: 'good', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} succeed (${env.BUILD_URL})"
	}
	catch (exception)
	{
		slackSend color: 'bad', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} failed (${env.BUILD_URL})"
		throw exception
	}
}