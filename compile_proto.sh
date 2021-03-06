#!/bin/bash
#
# build the protobuf classes from the .proto. Note tested with 
# protobuf 2.4.1. Current version is 2.5.0.
#
# Building: 
# 
# Running this script is only needed when the protobuf structures 
# have change.
#

project_base="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"



if [ -d ${project_base}/generated/data ]; then
echo "removing contents of ${project_base}/data"
rm -r ${project_base}/data/*
else
 echo "creating directory ${project_base}/data"
 mkdir "${project_base}/data"
fi


# which protoc that you built (if not in your path)
#PROTOC_HOME=/usr/local/protobuf-2.5.0/

if [ -d ${project_base}/generated ]; then
  echo "removing contents of ${project_base}/generated"
rm -r ${project_base}/generated/*
else
  echo "creating directory ${project_base}/generated"
  mkdir "${project_base}/generated"
fi

if [ -d ${project_base}/client/generatedpy ]; then
  echo "removing contents of ${project_base}/client/generatedpy"
  rm -r ${project_base}/client/generatedpy/*
else
  echo "creating directory ${project_base}/client/generatedpy"
  mkdir "${project_base}/client/generatedpy"
fi

touch ${project_base}/client/generatedpy/__init__.py

protoc --proto_path="${project_base}/resources" --java_out="${project_base}/generated" "${project_base}/resources/common.proto"
protoc --proto_path="${project_base}/resources" --java_out="${project_base}/generated" "${project_base}/resources/election.proto"
protoc --proto_path="${project_base}/resources" --java_out="${project_base}/generated" "${project_base}/resources/pipe.proto"
protoc --proto_path="${project_base}/resources" --java_out="${project_base}/generated" "${project_base}/resources/work.proto"
protoc --proto_path="${project_base}/resources" --java_out="${project_base}/generated" "${project_base}/resources/voteRequest.proto"
protoc --proto_path="${project_base}/resources" --java_out="${project_base}/generated" "${project_base}/resources/appendEntries.proto"

protoc --proto_path="${project_base}/resources" --python_out="${project_base}/client/generatedpy" "${project_base}/resources/common.proto"
protoc --proto_path="${project_base}/resources" --python_out="${project_base}/client/generatedpy" "${project_base}/resources/election.proto"
protoc --proto_path="${project_base}/resources" --python_out="${project_base}/client/generatedpy" "${project_base}/resources/pipe.proto"
protoc --proto_path="${project_base}/resources" --python_out="${project_base}/client/generatedpy" "${project_base}/resources/work.proto"
protoc --proto_path="${project_base}/resources" --python_out="${project_base}/client/generatedpy" "${project_base}/resources/voteRequest.proto"
protoc --proto_path="${project_base}/resources" --python_out="${project_base}/client/generatedpy" "${project_base}/resources/appendEntries.proto"
