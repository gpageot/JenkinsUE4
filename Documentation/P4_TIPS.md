# P4 tips

List of useful setup and commands for perforce

## P4 workspace

In P4v, menu "Connection > Edit Current workspace > Advanced >", I find the following option to be useful:

	Modtime
	Rmdir
	On submit "Don't submit unchanged files"

Note that P4V will create a new workspace for every stream, so you will need to repeat those changes for every workspaces.

## P4 client

You can right client in the workspace view and select "Open command window here" in order to run perforce commandline (It will use the login information from P4V)