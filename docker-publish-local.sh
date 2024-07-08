#!/bin/bash
set -e

# Create a release version of the ScalaJS client
cd draw-client
npm run build
cd ..

# Create a docker container (using the built release version)
sbt "project server; copyResources; Docker/publishLocal"
