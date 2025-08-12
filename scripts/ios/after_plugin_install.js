var helper = require("./helper");
const { setContext } = require('../lib/utilities');

module.exports = function(context) {
    setContext(context);

    // Add a build phase which runs a shell script that executes the Crashlytics
    // run command line tool which uploads the debug symbols at build time.
    var xcodeProjectPath = helper.getXcodeProjectPath();
    helper.removeShellScriptBuildPhase(context, xcodeProjectPath);
    helper.addShellScriptBuildPhase(context, xcodeProjectPath);
    helper.addGoogleTagManagerContainer(context, xcodeProjectPath);
};
