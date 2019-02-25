#!groovy

// Gregory Pageot
// 2019-02-01

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
	stage('Test status')
	{
		def previousBuildStatus = GetPreviousBuildStatusExceptAborted()
		echo "Previous (non-aborted)build status: ${previousBuildStatus}"

		def currentBuildSucceed = WANT_BUILD_FAIL.toBoolean() == false

		// Only if exactly matching SUCCESS (To manage first build 'unknown' status for example)
		def previousBuildSucceed = (previousBuildStatus == 'SUCCESS')
		def previousBuildFailed = previousBuildSucceed == false

		// Sleep 10s to give a chance to abort the build
		sleep 10

		if(currentBuildSucceed)
		{
			def buildFixed = previousBuildFailed
			echo "buildFixed: ${buildFixed?'yes':'no'}"
		}
		else
		{
			def buildFirstFail = previousBuildSucceed
			echo "buildFirstFail: ${buildFirstFail?'yes':'no'}"
			error('Failing build.')
		}
	}
}