__author__ = 'dan'

from dsl_parser import tasks
import json
from responses import BlueprintState, Execution, BlueprintValidationStatus
from workflow_client import workflow_client


class DslParseException(Exception):
    pass


class BlueprintsManager(object):

    def __init__(self):
        self.blueprints = {}
        self.executions = {}

    def blueprints_list(self):
        return self.blueprints.values()

    def get_blueprint(self, blueprint_id):
        return self.blueprints.get(blueprint_id, None)

    def get_execution(self, execution_id):
        return self.executions.get(execution_id, None)

    # TODO: call celery tasks instead of doing this directly here
    # TODO: prepare multi instance plan should be called on workflow execution
    def publish_blueprint(self, dsl_location, alias_mapping_url, resources_base_url):
        # TODO: error code if parsing fails (in one of the 2 tasks)
        try:
            plan = tasks.parse_dsl(dsl_location, alias_mapping_url, resources_base_url)
            plan = tasks.prepare_multi_instance_plan(json.loads(plan))
        except Exception:
            raise DslParseException
        new_blueprint = BlueprintState(json_plan=plan, plan=json.loads(plan))
        self.blueprints[str(new_blueprint.id)] = new_blueprint
        return new_blueprint

    # currently validation is split to 2 phases: the first part is during submission (dsl parsing)
    # second part is during call to validate which simply delegates the plan to the workflow service
    # so we can parse all the workflows and see things are ok
    def validate_blueprint(self, blueprint_id):
        blueprint = self.get_blueprint(blueprint_id)
        plan = blueprint.typed_plan
        response = workflow_client().validate_workflows(plan)
        # TODO raise error if error
        return BlueprintValidationStatus(blueprint_id=blueprint_id,
                                         status=response['status'])

    def execute_workflow(self, blueprint_id, workflow_id):
        blueprint = self.get_blueprint(blueprint_id)
        workflow = blueprint.typed_plan['workflows'][workflow_id]
        plan = blueprint.typed_plan
        response = workflow_client().execute_workflow(workflow, plan)
        # TODO raise error if there is error in response
        new_execution = Execution(state=response['state'],
                                  internal_workflow_id=response['id'],
                                  created_at=response['created'],
                                  blueprint_id=blueprint_id,
                                  workflow_id=workflow_id)
        blueprint.add_execution(new_execution)
        self.executions[str(new_execution.id)] = new_execution
        return new_execution

    def get_workflow_state(self, execution_id):
        execution = self.get_execution(execution_id)
        response = workflow_client().get_workflow_status(execution.internal_workflow_id)
        execution.status = response['state']
        if execution.status == 'failed':
            execution.error = response['error']
        return execution


_instance = BlueprintsManager()


def reset():
    global _instance
    _instance = BlueprintsManager()


def instance():
    return _instance
