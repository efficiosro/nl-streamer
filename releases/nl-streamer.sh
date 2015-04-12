#!/bin/sh

os_name=$(uname -o | tr '[:upper:]' '[:lower:]')
arch=$(uname -m | tr '[:upper:]' '[:lower:]')

if [ $arch != 'x86_64' ]; then
    arch='x86'
fi
if [ $os_name == 'darwin' ]; then
    os_name='macosx'
fi    
native_lib_path="native/$os_name/$arch"

java -Djava.library.path=${native_lib_path} \
     -jar nl-streamer.jar \
     $@
