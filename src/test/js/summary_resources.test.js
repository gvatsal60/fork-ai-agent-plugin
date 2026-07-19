const assert = require('node:assert/strict');
const test = require('node:test');

const {
  buildApprovalUrl,
  isSafeUrl
} = require('../../main/resources/io/jenkins/plugins/aiagentjob/AiAgentRunAction/summary_resources.js');

test('approval URL preserves invocation and encodes approval parameters', function () {
  const result = new URL(buildApprovalUrl(
    'https://jenkins.example/job/test/1/ai-agent/deny?invocation=7',
    'approval id',
    'needs review & retry'
  ));

  assert.equal(result.searchParams.get('invocation'), '7');
  assert.equal(result.searchParams.get('id'), 'approval id');
  assert.equal(result.searchParams.get('reason'), 'needs review & retry');
});

test('rendered links accept only safe protocols', function () {
  const baseUrl = 'https://jenkins.example/job/test/1/';

  assert.equal(isSafeUrl('/artifact/report.html', true, baseUrl), true);
  assert.equal(isSafeUrl('https://example.com/report', true, baseUrl), true);
  assert.equal(isSafeUrl('mailto:owner@example.com', true, baseUrl), true);
  assert.equal(isSafeUrl('javascript:alert(1)', true, baseUrl), false);
  assert.equal(isSafeUrl('data:text/html,unsafe', true, baseUrl), false);
});

test('rendered images reject mailto and non-web protocols', function () {
  const baseUrl = 'https://jenkins.example/job/test/1/';

  assert.equal(isSafeUrl('https://example.com/image.png', false, baseUrl), true);
  assert.equal(isSafeUrl('mailto:owner@example.com', false, baseUrl), false);
  assert.equal(isSafeUrl('file:///etc/passwd', false, baseUrl), false);
});
