# Additional setup

List of setup that could be useful to make to the engine while using a server

## Shared Derived data cache

### Setup
Epic documentation:
https://docs.unrealengine.com/en-us/Engine/Basics/DerivedDataCache

In Projects/PROJECT_NAME/Config/DefaultEngine.ini add the line

[DerivedDataBackendGraph]
Shared=(Type=FileSystem, ReadOnly=false, Clean=false, Flush=false, DeleteUnused=true, UnusedFileAge=19, FoldersToClean=-1, Path=\\Server-FileServer\UE4JenkinsShare\DDC, EnvPathOverride=UE-SharedDataCachePath)

Where \\\\Server-FileServer\UE4JenkinsShare\DDC is a network location with read-write access for your whole team

### Test results

Using a new project based on C++ 3rd person template

Default setup, only project DDC folder removed
- First run: 28 seconds
- Second run: 25 seconds

Default setup, Engine and project DDC folder removed
- First run: 2m29 seconds
- Second run: 28 seconds

After setting up the Shared DDC:

Engine, project DDC, Shared DDC folder removed
- First run: 2m30 seconds
Engine, project DDC, removed
- Second run: 30 seconds

## Swarm agent

### Setup
Epic documentation:
https://docs.unrealengine.com/en-us/Engine/Rendering/LightingAndShadows/Lightmass/Unreal-Swarm-Overview