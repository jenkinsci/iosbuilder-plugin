package org.jenkinsci.plugins.iosbuilder;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Node;
import hudson.util.QuotedStringTokenizer;
import org.jenkinsci.plugins.iosbuilder.signing.Identity;
import org.jenkinsci.plugins.iosbuilder.signing.Mobileprovision;
import org.jenkinsci.plugins.iosbuilder.signing.PKCS12Archive;

import java.util.*;

public class iOSBuilderExecutor {
    private final AbstractBuild build;
    private final Launcher launcher;
    private final BuildListener listener;
    private final String pod;
    private final String security;
    private final String xcodebuild;
    private final String xcrun;
    private EnvVars envVars = null;
    private Identity identity;
    private String keychainName;
    private Mobileprovision mobileprovision;
    private FilePath buildPath;

    iOSBuilderExecutor(String podPath, String securityPath, String xcodebuildPath, String xcrunPath, AbstractBuild build, Launcher launcher, BuildListener listener, String buildDirectory) throws Exception {
        this.pod = podPath;
        this.security = securityPath;
        this.xcodebuild = xcodebuildPath;
        this.xcrun = xcrunPath;
        this.build = build;
        this.launcher = launcher;
        this.listener = listener;
        try {
            envVars = build.getEnvironment(listener);
        }
        catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            throw new Exception("Could not get BuildListener environment");
        }
        this.buildPath = new FilePath(this.build.getWorkspace(), envVars.expand(buildDirectory));
    }

    int installPods(String projectRootPath) throws Exception {
        try {
            FilePath rootPath = new FilePath(build.getWorkspace(), envVars.expand(projectRootPath));
            String action = rootPath.child("Podfile.lock").exists() ? "update" : "install";
            return executeAt(rootPath, pod, action, "--no-color");
        }
        catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            throw new Exception("Can not install pods");
        }
    }

    int installIdentity(PKCS12Archive pkcs12Archive, Mobileprovision mobileprovision) {
        int result = 0;
        FilePath identityPath = null;
        try {
            identity = pkcs12Archive.chooseIdentity(mobileprovision.getCertificates());
            if (identity != null) {
                identityPath = build.getWorkspace().createTempFile("identity", ".p12");
                String identityPassword = UUID.randomUUID().toString();
                identity.save(identityPath.write(), identityPassword.toCharArray());

                //create a keychain, import identity
                keychainName = UUID.randomUUID().toString() + ".keychain";
                String keychainPassword = UUID.randomUUID().toString();
                execute(security, "create-keychain", "-p", keychainPassword, keychainName);
                execute(security, "import", identityPath.getRemote(), "-k", keychainName, "-P", identityPassword, "-A");
                execute(security, "unlock-keychain", "-p", keychainPassword, keychainName);
                execute(security, "set-keychain-settings", "-u", keychainName);
            }
        }
        catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            result = 1;
        }
        finally {
            try {
                identityPath.delete();
            }
            catch (Exception e) {
                e.printStackTrace(listener.getLogger());
            }
        }
        if (result == 0) {
            try {
                this.mobileprovision = mobileprovision;

                String mobileprovisionPath = "/Library/MobileDevice/Provisioning Profiles/" + mobileprovision.getUUID() + ".mobileprovision";
                String mobileprovisionAbsolutePath = envVars.get("HOME").replaceAll("/$", "") + mobileprovisionPath;
                Node node = build.getBuiltOn();
                FilePath mobileprovisionFilePath = node.createPath(mobileprovisionAbsolutePath);
                mobileprovisionFilePath.write().write(mobileprovision.getBytes());
            }
            catch (Exception e) {
                e.printStackTrace(listener.getLogger());
                result = 1;
            }
        }
        return result;
    }

    int runXcodebuild(String xcworkspacePath, String xcodeprojPath, String target, String scheme, String configuration, String sdk, String additionalParameters, boolean codeSign) {
        try {
            ArrayList<String> buildCommand = new ArrayList<String>();
            buildCommand.add(xcodebuild);
            if (xcworkspacePath != null && !xcworkspacePath.isEmpty()) {
                buildCommand.add("-workspace");
                buildCommand.add(xcworkspacePath);
            }
            if (xcodeprojPath != null && !xcodeprojPath.isEmpty()) {
                buildCommand.add("-project");
                buildCommand.add(xcodeprojPath);
            }
            if (target != null && !target.isEmpty()) {
                buildCommand.add("-target");
                buildCommand.add(target);
            }
            if (scheme != null && !scheme.isEmpty()) {
                buildCommand.add("-scheme");
                buildCommand.add(scheme);
            }
            if (configuration != null && !configuration.isEmpty()) {
                buildCommand.add("-configuration");
                buildCommand.add(configuration);
            }
            buildCommand.add("-sdk");
            buildCommand.add(sdk);
            buildCommand.add("CONFIGURATION_BUILD_DIR=" + buildPath);
            if (additionalParameters != null && !additionalParameters.isEmpty()) {
                for (String parameter : QuotedStringTokenizer.tokenize(envVars.expand(additionalParameters))) {
                    buildCommand.add(parameter);
                }
            }
            if (codeSign) {
                if (mobileprovision != null) {
                    buildCommand.add("PROVISIONING_PROFILE="+ mobileprovision.getUUID());
                }
                if (identity != null) {
                    buildCommand.add("CODE_SIGN_IDENTITY=" + identity.getCommonName());
                    buildCommand.add("OTHER_CODE_SIGN_FLAGS=--keychain " + keychainName);
                }
            }
            return executeAt(build.getWorkspace(), buildCommand);
        }
        catch (Exception e) {
            e.printStackTrace(listener.getLogger());
        }
        return 0;
    }

    int buildIpa(String ipaNameTemplate) {
        try {
            ipaNameTemplate = envVars.expand(ipaNameTemplate);
            List<FilePath> filePaths = buildPath.list();
            for (Iterator<FilePath> iterator = filePaths.iterator(); iterator.hasNext(); ) {
                FilePath filePath = iterator.next();
                if (filePath.isDirectory() && filePath.getName().endsWith("app")) {
                    String outFileName = getFileBasenameWithTemplate(filePath, ipaNameTemplate, "\\.app$") + ".ipa";
                    FilePath outFilePath = build.getWorkspace().child(outFileName);
                    outFilePath.getParent().mkdirs();
                    executeAt(buildPath, xcrun, "-sdk", "iphoneos", "PackageApplication", filePath.getName(), "-o", outFilePath.getRemote());
                }
            }
        } catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            return 1;
        }
        return 0;
    }

    int zipDSYM(String dSYMNameTemplate) {
        try {
            dSYMNameTemplate = envVars.expand(dSYMNameTemplate);
            List<FilePath> filePaths = buildPath.list();
            for (Iterator<FilePath> iterator = filePaths.iterator(); iterator.hasNext(); ) {
                FilePath filePath = iterator.next();
                if (filePath.isDirectory() && filePath.getName().endsWith("app.dSYM")) {
                    String outFileName = getFileNameWithTemplate(filePath, dSYMNameTemplate, "\\.app\\.dSYM$") + ".zip";
                    FilePath outFilePath = build.getWorkspace().child(outFileName);
                    outFilePath.getParent().mkdirs();
                    filePath.zip(outFilePath);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(listener.getLogger());
            return 1;
        }
        return 0;
    }

    void cleanup() {
        try {
            execute(security, "delete-keychain", keychainName);
        }
        catch (Exception e) {
            e.printStackTrace(listener.getLogger());
        }
    }

    private String getFileNameWithTemplate(FilePath filePath, String template, String extensionRegex) {
        String fileBasename = filePath.getName().replaceAll(extensionRegex, "");
        String newFileBasename = template.replaceAll("\\$APP_NAME", fileBasename);
        return filePath.getName().replaceFirst(fileBasename, newFileBasename);
    }

    private String getFileBasenameWithTemplate(FilePath filePath, String template, String extensionRegex) {
        String fileBasename = filePath.getName().replaceAll(extensionRegex, "");
        return template.replaceAll("\\$APP_NAME", fileBasename);
    }

    private int execute(String... args) throws Exception {
        return launcher.launch().envs(envVars).stdout(listener).stderr(listener.getLogger()).cmds(args).join();
    }

    private int executeAt(FilePath path, String... args) throws Exception {
        return launcher.launch().pwd(path).envs(envVars).stdout(listener).stderr(listener.getLogger()).cmds(args).join();
    }

    private int executeAt(FilePath path, List<String> args) throws Exception {
        return launcher.launch().pwd(path).envs(envVars).stdout(listener).stderr(listener.getLogger()).cmds(args).join();
    }
}
