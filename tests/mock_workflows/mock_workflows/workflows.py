__author__ = 'dan'

import time
from cloudify.decorators import workflow
from cloudify.workflows import api
from cloudify.workflows.tasks_graph import TaskDependencyGraph


@workflow
def execute_operation(ctx, operation, properties, node_id, **_):
    node_instance = list(ctx.get_node(node_id).instances)[0]
    node_instance.execute_operation(
        operation=operation,
        kwargs=properties).apply_async().get()


@workflow
def sleep(ctx, **kwargs):
    node_instance = next(next(ctx.nodes).instances)

    node_instance.execute_operation(
        'test_interface.operation',
        kwargs={'key': 'before-sleep',
                'value': None}).apply_async()
    time.sleep(10)
    node_instance.execute_operation(
        'test_interface.operation',
        kwargs={'key': 'after-sleep',
                'value': None}).apply_async()


@workflow
def sleep_with_cancel_support(ctx, **kwargs):
    node_instance = next(next(ctx.nodes).instances)

    node_instance.execute_operation(
        'test_interface.operation',
        kwargs={'key': 'before-sleep',
                'value': None}).apply_async()

    is_cancelled = False
    for i in range(10):
        if api.has_cancel_request():
            is_cancelled = True
            break
        time.sleep(1)

    if is_cancelled:
        return api.EXECUTION_CANCELLED_RESULT

    node_instance.execute_operation(
        'test_interface.operation',
        kwargs={'key': 'after-sleep',
                'value': None}).apply_async()


@workflow
def sleep_with_graph_usage(ctx, **kwargs):

    graph = TaskDependencyGraph(ctx)

    sequence = graph.sequence()

    node_instance = next(next(ctx.nodes).instances)

    sequence.add(
        node_instance.execute_operation(
            'test_interface.operation',
            kwargs={'key': 'before-sleep',
                    'value': None}),
        node_instance.execute_operation(
            'test_interface.sleep_operation',
            kwargs={'sleep': '10'}),
        node_instance.execute_operation(
            'test_interface.operation',
            kwargs={'key': 'after-sleep',
                    'value': None}))

    return graph.execute()
