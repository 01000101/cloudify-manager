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

ENV['RACK_ENV'] = 'test'

require '../app'
require 'test/unit'
require 'rack/test'
require 'json'

class RuoteRestApiTest < Test::Unit::TestCase
  include Rack::Test::Methods

  def app
    RuoteServiceApp.new
  end

  def test_root
    get '/'
    assert_successful_response parsed_response
  end

  def test_launch
    radial = %/
define wf
  echo 'hello!'
/
    post '/workflows', {
        :radial => radial
    }.to_json
    assert_equal 201, last_response.status
    res = JSON.parse(last_response.body, :symbolize_names => true)
    assert_equal 'workflow_state', res[:type]
    assert_equal 'pending', res[:state]
    assert_includes res.keys, :id, :created
    assert_equal false, res[:id].empty?
    assert_equal false, res[:created].empty?
  end

  def test_launch_fields
    radial = %/
define wf
  echo '$key'
/
    fields = {
        :key => 'value'
    }
    post '/workflows', {
        :radial => radial,
        :fields => fields
    }.to_json
    res = JSON.parse(last_response.body, :symbolize_names => true)
    wait_for_workflow_state(res[:id], :terminated, 5)
  end

  def test_launch_wrong_fields_type
    radial = %/
define wf
  echo '$key'
/
    fields = [
        :key => 'value'
    ]
    begin
      post '/workflows', {
          :radial => radial,
          :fields => fields
      }.to_json
      assert_fail_assertion 'expected exception'
    rescue
      # expected...
    end
  end

  def test_get_workflow_state
    radial = %/
define wf
  echo 'hello!'
/
    post '/workflows', {
        :radial => radial
    }.to_json
    assert_equal 201, last_response.status
    res = JSON.parse(last_response.body, :symbolize_names => true)
    wfid = res[:id]
    get "/workflows/#{wfid}"
    res = JSON.parse(last_response.body, :symbolize_names => true)
    assert_equal 'workflow_state', res[:type]
    assert_equal wfid, res[:id]
    assert_include %w(pending launched terminated), res[:state]
  end

  def wait_for_workflow_state(wfid, state, timeout=5)
    deadline = Time.now + timeout
    state_ok = false
    res = nil
    while not state_ok and Time.now < deadline
      begin
        get "/workflows/#{wfid}"
        res = JSON.parse(last_response.body, :symbolize_names => true)
        assert_equal state.to_s, res[:state]
        state_ok = true
      rescue Exception
        sleep 0.5
      end
    end
    unless state_ok
      get "/workflows/#{wfid}"
      res = JSON.parse(last_response.body, :symbolize_names => true)
      puts "res: #{res[:state]}"
      assert_equal state.to_s, res[:state]
    end
    res
  end

  def test_get_workflows
    radial = %/
define wf
  echo 'hello!'
/
    post '/workflows', {
        :radial => radial
    }.to_json
    get '/workflows'
    res = parsed_response
    assert_successful_response res
    data = res[:data]
    assert data.size > 0
  end

  def parsed_response
    JSON.parse(last_response.body, :symbolize_names => true)
  end

  def assert_successful_response(response)
    assert_equal 'success', response[:status]
  end

end