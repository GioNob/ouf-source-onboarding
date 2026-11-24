// Serve THS assets exactly as the owner does: page scripts carry the shared session code.
const fs = require('fs'), path = require('path');
const ROOT = path.resolve(__dirname, '../../src/main/resources/ths');
const PAGE_SCRIPTS = new Set(['workflow-review.js', 'permissions.js', 'managed-file-picker.js', 'profile-recovery.js', 'resolution.js']);
module.exports = name => {
  const body = fs.readFileSync(path.join(ROOT, name));
  return PAGE_SCRIPTS.has(name) ? Buffer.concat([fs.readFileSync(path.join(ROOT, 'session.js')), Buffer.from([10, 59, 10]), body]) : body;
};
