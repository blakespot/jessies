#!/usr/bin/ruby -w

require 'fileutils.rb'
require 'pathname.rb'

# Cope with symbolic links to this script.
salma_hayek = Pathname.new("#{__FILE__}/..").realpath().dirname()
require "#{salma_hayek}/bin/invoke-java.rb"

invoke_java("JavaHpp", "", "e/tools/JavaHpp", [])
