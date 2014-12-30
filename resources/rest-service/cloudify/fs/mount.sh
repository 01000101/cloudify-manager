#!/bin/bash -e

fs_mount_path=$(ctx node properties fs_mount_path)
filesys=$(ctx node instance runtime-properties filesys)

if [ ! -f ${fs_mount_path} ]; then
    sudo mkdir -p ${fs_mount_path}
fi

ctx logger info "Mounting file system on ${fs_mount_path}"
sudo mount ${filesys} ${fs_mount_path}
