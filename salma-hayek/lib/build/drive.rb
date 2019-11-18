#!/usr/bin/ruby -w

# This was added to avoid an issue with the jwt gem for ruby1.8 in swiftboat's Squeeze chroot.
# But stopped uploads from the Mac rat2.
if RUBY_VERSION < "1.9" && File.exist?("/usr/bin/ruby1.9.1")
  exec("ruby1.9.1", $0, *ARGV)
end

$PREVIOUS_VERBOSE = $VERBOSE
$VERBOSE = false

# Copyright (C) 2012 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
require 'rubygems'
require 'google/api_client'
require 'google/api_client/client_secrets'
require 'google/api_client/auth/file_storage'
require 'google/api_client/auth/installed_app'
require 'logger'

$VERBOSE = $PREVIOUS_VERBOSE

module Google
  class APIClient
    module ENV
      # Apply the key part of:
      # https://github.com/googleapis/google-api-ruby-client/pull/648/commits/ee8c922dbf9cef79284d4b83d16d4d73008a6e8f
      # ... to avoid:
      # /usr/lib/ruby/2.5.0/net/http/header.rb:23:in `block in initialize_http_header': header ("User-Agent") field value ("Ruby Drive sample/1.0.0 google-api-ruby-client/0.8.7 Linux/4.19.0-6-amd64\n (gzip)") cannot include CR/LF (ArgumentError)
      OS_VERSION = `uname -sr`.chomp().sub(' ', '/')
    end
  end
end

API_VERSION = 'v2'
CACHED_API_FILE = "drive-#{API_VERSION}.cache"
CREDENTIAL_STORE_FILE = "#{$0}-oauth2.json"

# Handles authentication and loading of the API.
def setup()
  log_file = File.open('drive.log', 'a+')
  log_file.sync = true
  logger = Logger.new(log_file)
  logger.level = Logger::DEBUG

  client = Google::APIClient.new(:application_name => 'Ruby Drive sample',
      :application_version => '1.0.0')

  $PREVIOUS_VERBOSE = $VERBOSE
  $VERBOSE = false
  # FileStorage stores auth credentials in a file, so they survive multiple runs
  # of the application. This avoids prompting the user for authorization every
  # time the access token expires, by remembering the refresh token.
  # Note: FileStorage is not suitable for multi-user applications.
  file_storage = Google::APIClient::FileStorage.new(CREDENTIAL_STORE_FILE)
  $VERBOSE = $PREVIOUS_VERBOSE
  if file_storage.authorization.nil?
    client_secrets = Google::APIClient::ClientSecrets.load
    # The InstalledAppFlow is a helper class to handle the OAuth 2.0 installed
    # application flow, which ties in with FileStorage to store credentials
    # between runs.
    flow = Google::APIClient::InstalledAppFlow.new(
      :client_id => client_secrets.client_id,
      :client_secret => client_secrets.client_secret,
      :scope => ['https://www.googleapis.com/auth/drive']
    )
    # Launchy 2.4.2 is broken on Cygwin64 (probably all of Windows) such that it runs
    # itself rather than the "start" command.
    # Even if I hack out the erroneous "launchy" argument from browser.rb (see the check-in comment),
    # start jibs at the URL for unspecified reasons, possibly related to quoting or length.
    # By turning on the debug, you get to see the requested URL, which you can copy and paste.
    # Once you click Accept in Chrome, everything else moves along automatically.
    ENV["LAUNCHY_DEBUG"] = "true"
    client.authorization = flow.authorize(file_storage)
  else
    client.authorization = file_storage.authorization
  end

  drive = nil
  # Load cached discovered API, if it exists. This prevents retrieving the
  # discovery document on every run, saving a round-trip to API servers.
  if File.exist?(CACHED_API_FILE)
    File.open(CACHED_API_FILE) do |file|
      drive = Marshal.load(file)
    end
  else
    drive = client.discovered_api('drive', API_VERSION)
    File.open(CACHED_API_FILE, 'w') do |file|
      Marshal.dump(drive, file)
    end
  end

  return client, drive
end

def insert_file(client, drive, title, description, parentId, mimeType, fileName)
  file = drive.files.insert.request_schema.new({
    "title" => title,
    "description" => description,
    "mimeType" => mimeType
  })
  file.parents = [{"id" => parentId}]

  media = Google::APIClient::UploadIO.new(fileName, mimeType)
  result = client.execute(
    :api_method => drive.files.insert,
    :body_object => file,
    :media => media,
    :parameters => {
      "uploadType" => "multipart",
      "alt" => "json"
    }
  )

  #jj(result.data().to_hash())
  if result.status() != 200
    raise(result.inspect())
  end
  return result.data().id()
end

def find_file(client, drive, parentId, title)
  parameters = {
    "folderId" => parentId,
    "q" => "title='#{title}'"
  }
  result = client.execute(
    :api_method => drive.children.list,
    :parameters => parameters)
  #jj(result.data().to_hash())
  if result.status() != 200
    raise(result.inspect())
  end
  items = result.data().items()
  if items == []
    return nil
  end
  if items.size() != 1
    raise("What is this Drive madness, with #{items.size()} files all called #{title.inspect()}?")
  end
  item = items[0]
  id = item.id()
  return id
end

def copy_file(client, drive, title, parentId, fileId)
  file = drive.files.copy.request_schema.new({
    "title" => title,
  })
  file.parents = [{"id" => parentId}]
  parameters = {
    "fileId" => fileId,
  }
  
  result = client.execute(
    :api_method => drive.files.copy,
    :body_object => file,
    :parameters => parameters)
  
  #jj(result.data().to_hash())
  if result.status() != 200
    raise(result.inspect())
  end
end

def update_file(client, drive, mimeType, fileId, fileName)
  file = drive.files.update.request_schema.new({
  })
  parameters = {
    "fileId" => fileId,
    "uploadType" => "multipart",
  }
  
  media = Google::APIClient::UploadIO.new(fileName, mimeType)
  result = client.execute(
    :api_method => drive.files.update,
    :body_object => file,
    :media => media,
    :parameters => parameters)
  
  #jj(result.data().to_hash())
  if result.status() != 200
    raise(result.inspect())
  end
end

if __FILE__ == $0
  description = ARGV.shift()
  parentId = ARGV.shift()
  mimeType = ARGV.shift()
  fileName = ARGV.shift()
  latestDirectory = ARGV.shift()
  latestLink = ARGV.shift()
  if latestLink == nil || ARGV.empty?() == false
    raise("Syntax: drive.rb <description> <id of parent directory> <mime type> <filename> <latest directory> <latest link>")
  end
  title = fileName.sub(/^.*\//, "")
  client, drive = setup()
  if find_file(client, drive, parentId, title)
    exit(0)
  end
  fileId = insert_file(client, drive, title, description, parentId, mimeType, fileName)
  latestFile = find_file(client, drive, latestDirectory, latestLink)
  if latestFile
    update_file(client, drive, mimeType, latestFile, fileName)
  else
    copy_file(client, drive, latestLink, latestDirectory, fileId)
  end
end

# The first time you try to upload from a machine, you need to get salma-hayek/lib/build/client_secrets.json.
# Navigate to https://console.developers.google.com/apis/credentials?pli=1&project=jessies-downloads-uploader
# and select Native client 1.

# I hadn't uploaded from this machine for a few months when I got the following error.
# Removing salma-hayek/lib/build/drive.rb-oauth2.json is the first step to fix it.
# Then, on trying to make native-dist in eg terminator, you should get
# a browser pop to confirm access, modulo the caveat above re Launchy.
# /Users/mad/.gem/ruby/1.8/gems/signet-0.5.0/lib/signet/oauth_2/client.rb:885:in `fetch_access_token': Authorization failed.  Server message: (Signet::AuthorizationError)
# {
#  "error" : "invalid_grant"
# }
# 	from /Users/mad/.gem/ruby/1.8/gems/signet-0.5.0/lib/signet/oauth_2/client.rb:898:in `fetch_access_token!'
# 	from /Library/Ruby/Gems/1.8/gems/google-api-client-0.7.1/lib/google/api_client/auth/file_storage.rb:51:in `load_credentials'
# 	from /Library/Ruby/Gems/1.8/gems/google-api-client-0.7.1/lib/google/api_client/auth/file_storage.rb:46:in `open'
# 	from /Library/Ruby/Gems/1.8/gems/google-api-client-0.7.1/lib/google/api_client/auth/file_storage.rb:46:in `load_credentials'
# 	from /Library/Ruby/Gems/1.8/gems/google-api-client-0.7.1/lib/google/api_client/auth/file_storage.rb:39:in `initialize'
#  	from ./drive.rb:48:in `new'
# 	from ./drive.rb:48:in `setup'
#	from ./drive.rb:138
