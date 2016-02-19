package org.nuxeo.addon;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.ExceptionUtils;
import org.nuxeo.connect.client.vindoz.InstallAfterRestart;
import org.nuxeo.connect.client.we.StudioSnapshotHelper;
import org.nuxeo.connect.connector.ConnectServerError;
import org.nuxeo.connect.data.DownloadablePackage;
import org.nuxeo.connect.data.DownloadingPackage;
import org.nuxeo.connect.packages.PackageManager;
import org.nuxeo.connect.packages.dependencies.TargetPlatformFilterHelper;
import org.nuxeo.connect.update.LocalPackage;
import org.nuxeo.connect.update.PackageException;
import org.nuxeo.connect.update.PackageState;
import org.nuxeo.connect.update.PackageUpdateService;
import org.nuxeo.connect.update.ValidationStatus;
import org.nuxeo.connect.update.task.Task;
import org.nuxeo.ecm.admin.runtime.PlatformVersionHelper;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.runtime.api.Framework;

/**
 * @author karin
 */
@Operation(id = HotReloadOperation.ID, category = Constants.CAT_EXECUTION, label = "Hot Reload", description = "Updates Nuxeo Platform with changes made in Nuxeo Studio")
public class HotReloadOperation {

	public static final String ID = "Document.HotReloadOperation";

	@Context
	protected CoreSession session;

	protected boolean isStudioSnapshopUpdateInProgress;

	@Param(name = "validate", required = false)
	protected boolean validate;

	@Context
	protected PackageManager pm;

	protected String packageId;

	protected static final Log log = LogFactory.getLog(HotReloadOperation.class);

	@OperationMethod
	public void run() throws Exception {
		if (isStudioSnapshopUpdateInProgress) {
			return;
		}

		if (!((NuxeoPrincipal) session.getPrincipal()).isAdministrator()) {
			throw new NuxeoException("Must be Administrator to use this function");
		}

		// pm = Framework.getLocalService(PackageManager.class);
		List<DownloadablePackage> pkgs = pm.listRemoteAssociatedStudioPackages();
		DownloadablePackage snapshotPkg = StudioSnapshotHelper.getSnapshot(pkgs);

		if (snapshotPkg == null) {
			throw new NuxeoException("No Snapshot Package was found.");
		}

		packageId = snapshotPkg.getId();
		isStudioSnapshopUpdateInProgress = true;
		try {
			run();
		} finally {
			isStudioSnapshopUpdateInProgress = false;
		}
	}

	public void hotReloadPackage() {
		if (validate) {
			pm.flushCache();
			DownloadablePackage remotePkg = pm.findRemotePackageById(packageId);
			if (remotePkg == null) {
				throw new NuxeoException(
						String.format("Cannot perform validation: remote package '%s' not found", packageId));
			}

			String targetPlatform = PlatformVersionHelper.getPlatformFilter();
			if (!TargetPlatformFilterHelper.isCompatibleWithTargetPlatform(remotePkg, targetPlatform)) {
				throw new NuxeoException(
						String.format("This package is not validated for your current platform: %s", targetPlatform));
			}
		}

		// Effective install
		if (Framework.isDevModeSet()) {
			try {
				PackageUpdateService pus = Framework.getLocalService(PackageUpdateService.class);
				LocalPackage pkg = pus.getPackage(packageId);

				// Uninstall and/or remove if needed
				if (pkg != null) {
					log.info(String.format("Removing package %s before update...", pkg));
					if (pkg.getPackageState().isInstalled()) {
						// First remove it to allow SNAPSHOT upgrade
						log.info("Uninstalling " + packageId);
						Task uninstallTask = pkg.getUninstallTask();
						try {
							performTask(uninstallTask);
						} catch (PackageException e) {
							uninstallTask.rollback();
							throw e;
						}
					}
					pus.removePackage(packageId);
				}

				// Download
				DownloadingPackage downloadingPkg = pm.download(packageId);
				while (!downloadingPkg.isCompleted()) {
					log.debug("downloading studio snapshot package");
					Thread.sleep(100);
				}

				// Install
				log.info("Installing " + packageId);
				pkg = pus.getPackage(packageId);
				if (pkg == null || PackageState.DOWNLOADED != pkg.getPackageState()) {
					throw new NuxeoException("Error while downloading studio snapshot " + pkg);
				}
				Task installTask = pkg.getInstallTask();
				try {
					performTask(installTask);
				} catch (PackageException e) {
					installTask.rollback();
					throw e;
				}
			} catch (InterruptedException e) {
				ExceptionUtils.checkInterrupt(e);
				throw new NuxeoException("Error while downloading studio snapshot", e);
			} catch (PackageException | ConnectServerError e) {
				throw new NuxeoException("Error while installing studio snapshot", e);
			}
		} else {
			InstallAfterRestart.addPackageForInstallation(packageId);
		}
	}

	protected void performTask(Task task) throws PackageException {
		ValidationStatus validationStatus = task.validate();
		if (validationStatus.hasErrors()) {
			throw new PackageException(
					"Failed to validate package " + task.getPackage().getId() + " -> " + validationStatus.getErrors());
		}
		if (validationStatus.hasWarnings()) {
			log.warn("Got warnings on package validation " + task.getPackage().getId() + " -> "
					+ validationStatus.getWarnings());
		}
		task.run(null);
	}
}
