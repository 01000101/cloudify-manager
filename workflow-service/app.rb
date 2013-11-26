#########
# Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
#  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  * See the License for the specific language governing permissions and
#  * limitations under the License.
#

require 'sinatra'
require 'json'
require_relative 'ruote/ruote_workflow_engine'

$ruote_service = RuoteWorkflowEngine.new(:test => settings.test?)


class RuoteServiceApp < Sinatra::Base

  configure do
    set :show_exceptions => false
    set :raise_errors => true
  end

  get '/' do
    'ruote-rest-api'
  end

  get '/workflows' do
    response = {
        :workflows => []
    }
    response.to_json
  end

  post '/workflows' do
    req = self.parse_request_body(request)
    raise 'radial key is missing in request' unless req.has_key?(:radial)
    fields = {}
    if req.has_key?(:fields)
      fields_type = type(req[:fields])
      raise "fields value type is expected to be hash/map but is #{fields_type}" unless fields_type.eql?(Hash)
      fields = req[:fields]
    end
    $ruote_service.launch(req[:radial], fields).to_json
  end

  get '/workflows/:id' do
    wfid = params[:id]
    $ruote_service.get_workflow_state(wfid).to_json
  end

  def parse_request_body(request)
    JSON.parse(request.body.read, :symbolize_names => true)
  end

end


