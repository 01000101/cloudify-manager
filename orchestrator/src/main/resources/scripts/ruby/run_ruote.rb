#/*******************************************************************************
# * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
# *
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# *
# *       http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
#    * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    * See the License for the specific language governing permissions and
#    * limitations under the License.
# *******************************************************************************/

#####
# Ruby script for running ruote workflows.
# Workflow is injected to the script using the $workflow variable.
#
# Additionally, a ruote participant for running java code is included.
#
# author: Idan Moyal
# since: 0.1
#

require 'rubygems'
require 'ruote'
require 'net/http'
require 'java'

class JavaClassParticipant < Ruote::Participant
  def on_workitem
    puts '--- JavaClassParticipant invocation:'

    puts 'workitem.fields:'
    puts workitem.fields

    java_class_name = workitem.params['class']
    java_participant = eval("#{java_class_name}.new")
    java_participant.execute()

    puts '--- DONE.'
    puts "\n"

    reply
  end
end

class RestGetParticipant < Ruote::Participant
  def on_workitem
    id = workitem.params['id']
    host = $ruote_properties.get("rest_get.#{id}.host")
    path = $ruote_properties.get("rest_get.#{id}.path")
    timeout = $ruote_properties.get("rest_get.#{id}.timeout")
    expected = $ruote_properties.get("rest_get.#{id}.response")
    uri = URI.parse("#{host}#{path}")
    http = Net::HTTP.start(uri.host, uri.port)

    start = Time.now
    success = false
    while true do
      response = http.send_request('GET', uri.request_uri)
      if response.body == expected
        success = true
        break
      end
      delta = (Time.now - start).to_i
      if delta >= timeout.to_i
        break
      end
      puts "rest_get.#{id}:: waiting for response to be: '#{expected}'"
      sleep(1)
    end
    if success
      reply
    end
  end
end

class RestPutParticipant < Ruote::Participant
  def on_workitem
    id = workitem.params['id']
    host = $ruote_properties.get("rest_put.#{id}.host")
    path = $ruote_properties.get("rest_put.#{id}.path")
    p_alias = "rest_put.#{id}"

    url = "#{host}#{path}"
    uri = URI.parse(url)

    puts "#{p_alias}:: request: host=#{uri.host} port=#{uri.port} path=#{path} url=#{url}"

    http = Net::HTTP.start(uri.host, uri.port)
    response = http.send_request('PUT', uri.request_uri)

    puts "#{p_alias}:: response from #{url} is:"
    puts "#{p_alias}:: response.code = #{response.code}"
    puts "#{p_alias}:: response.body = #{response.body}"

    reply
  end
end

appliances = $ruote_properties.get('appliances')

engine = Ruote::Engine.new(Ruote::Worker.new(Ruote::HashStorage.new))
engine.register_participant 'java', JavaClassParticipant
engine.register_participant 'rest_get', RestGetParticipant
engine.register_participant 'rest_put', RestPutParticipant


radial_workflow = Ruote::RadialReader.read($workflow)

wfid = engine.launch(radial_workflow, 'appliances' => appliances)
engine.wait_for(wfid)
