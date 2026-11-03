package com.lagdaemon.creationremote.gimbal

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guard the receive-only boundary and narrowly scoped Android share provider. */
class DiagnosticSafetyTest {
    private fun appDir():File = sequenceOf(File("."),File("app")).first{File(it,"src/main/AndroidManifest.xml").exists()}
    @Test fun onlyStandardSubscriptionWritesArePresent(){
        val source=File(appDir(),"src/main/java/com/lagdaemon/creationremote/gimbal/BleDiagnostics.kt").readText()
        for(forbidden in listOf("writeCharacteristic(","readCharacteristic(","createBond(","removeBond(","setPin(","BluetoothSocket","ENABLE_"+"MOTOR"))assertFalse(forbidden,source.contains(forbidden))
        assertTrue(source.contains("writeDescriptor(descriptor,value)"))
        assertTrue(source.contains("BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE"))
        assertTrue(source.contains("BluetoothGattDescriptor.ENABLE_INDICATION_VALUE"))
        assertTrue(source.contains("descriptor.uuid!=cccd"))
    }
    @Test fun sharedReportsHaveReadOnlyScopedProvider(){
        val root=appDir()
        val manifest=File(root,"src/main/AndroidManifest.xml").readText()
        val paths=File(root,"src/main/res/xml/diagnostic_report_paths.xml").readText()
        val ui=File(root,"src/main/java/com/lagdaemon/creationremote/gimbal/BleDiagnosticsScreen.kt").readText()
        val provider=manifest.substringAfter("<provider").substringBefore("</provider>")
        assertTrue(provider.contains("android:exported=\"false\""));assertTrue(provider.contains("android:grantUriPermissions=\"true\""))
        assertTrue(paths.contains("path=\"osmo-diagnostics/\""));assertFalse(paths.contains("root-path"));assertFalse(paths.contains("path=\".\""))
        assertTrue(ui.contains("Intent.ACTION_SEND"));assertTrue(ui.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertFalse(ui.contains("FLAG_GRANT_WRITE_URI_PERMISSION"));assertFalse(ui.contains("CreateDocument"))
    }
}
