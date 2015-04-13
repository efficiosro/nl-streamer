#!/bin/bash

os_name=$(uname -o | tr '[:upper:]' '[:lower:]')
arch=$(uname -m | tr '[:upper:]' '[:lower:]')

if [ $arch != 'x86_64' ]; then
    arch='x86'
fi
case $os_name in
    'darwin') os_name='macosx';;
    *'linux'*) os_name='linux';;
    *) os_name='windows';;
esac
native_lib_path="native/$os_name/$arch"

java -Djava.library.path=${native_lib_path} \
     -jar nl-streamer.jar \
     $@
