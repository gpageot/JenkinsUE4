#!groovy

// Gregory Pageot
// 2020-05-26
// https://github.com/gpageot/JenkinsUE4
// 
// Brief:
// Test slack integration
// 
// WARNING:
// Need slack plugin to be installed and configured
// See https://plugins.jenkins.io/slack/

node
{
	stage('Slack test')
	{
		slackSend color: 'good', message: "${env.JOB_NAME} ${env.BUILD_NUMBER} succeed (${env.BUILD_URL})"
	}
}
