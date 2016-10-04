# this is the python script that fetches and applies an RE patch for compiling against the internal repos.

import subprocess
import urllib2
import sys
import os

patchFile = "hive-source-2.5.patch"

#### try to revert patch if it exists
statusOut = subprocess.check_output(["git", "status"])
if (("modified" in statusOut) and ("pom.xml" in statusOut)):
  if os.path.exists(patchFile):
    try:
      patchApplyResponse = subprocess.check_output(["git", "apply", "-R", patchFile])
    except:
      pass
#### done reverting patch

#### remove the old patch file if it exists
try:
  os.remove(patchFile)
except OSError:
  pass
#### done removing the old patch file

#### fetch the patch file
url = 'http://dev.hortonworks.com.s3.amazonaws.com/HDP/centos6/2.x/PATCH_FILES/2.5.0.0/patch_files/hive-source.patch'
response = urllib2.urlopen(url)
downloadPatch = response.read()
#### end of fetch of patch file

#### write out the patch file
f = open(patchFile, 'w')
f.write(downloadPatch)
f.close()
#### end of writing out the patch file

#### check if there are uncommitted changes
statusOut = subprocess.check_output(["git", "status"])
if ("modified" in statusOut):
  print "You have uncommitted changes in this workspace. Please stash or commit them before proceeding."
  sys.exit(1);
#### done with check for uncommitted changes

#### apply the patch
patchApplyResponse = subprocess.check_output(["git", "apply", "--reject", patchFile])
#### done applying the patch file

#### Maven command line
commandLine = ["mvn"]
first = True;

for arg in sys.argv:
  # first arg is the python script itself. skip it.
  if (first == True):
    first = False;
    continue;

  args = arg.split()
  for arg in args:
    commandLine.append(arg)

command = ""
leavePatch = True
ignoreNext = False
for arg in commandLine:
  if (ignoreNext == True):
    ignoreNext = False
    continue
  if (arg == "-f"):
    ignoreNext = True
    continue

  if (arg == "-Phdp"):
    continue
  if (arg == "-DcleanUp"):
    leavePatch = False
    continue

  command += " " + arg

#### trigger the build
process = subprocess.Popen([command], shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1)
while True:
  nextline = process.stdout.readline()
  if nextline == '' and process.poll() != None:
    break
  sys.stdout.write(nextline)
  sys.stdout.flush()

output = process.communicate()[0]
exitCode = process.returncode

if (exitCode != 0):
  print output
  print "Reverting patch because of failure to build"
  patchApplyResponse = subprocess.check_output(["git", "apply", "-R", patchFile])
  sys.exit(exitCode)
#### done triggering the build

if (leavePatch == False):
  patchApplyResponse = subprocess.check_output(["git", "apply", "-R", patchFile])
