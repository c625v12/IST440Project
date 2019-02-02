package com.IST440.application;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.mbientlab.metawear.CodeBlock;
import com.mbientlab.metawear.Data;
import com.mbientlab.metawear.MetaWearBoard;
import com.mbientlab.metawear.Route;
import com.mbientlab.metawear.Subscriber;
import com.mbientlab.metawear.android.BtleService;
import com.mbientlab.metawear.builder.RouteBuilder;
import com.mbientlab.metawear.builder.RouteComponent;
import com.mbientlab.metawear.builder.filter.Comparison;
import com.mbientlab.metawear.builder.filter.ThresholdOutput;
import com.mbientlab.metawear.builder.function.Function1;
import com.mbientlab.metawear.data.AngularVelocity;
import com.mbientlab.metawear.data.MagneticField;
import com.mbientlab.metawear.data.Quaternion;
import com.mbientlab.metawear.data.Acceleration;
import com.mbientlab.metawear.data.*;
import com.mbientlab.metawear.module.Accelerometer;
import com.mbientlab.metawear.module.AmbientLightLtr329;
import com.mbientlab.metawear.module.BarometerBosch;
import com.mbientlab.metawear.module.GyroBmi160;
import com.mbientlab.metawear.module.MagnetometerBmm150;
import com.mbientlab.metawear.module.SensorFusionBosch;
import com.mbientlab.metawear.module.Settings;
import com.mbientlab.metawear.module.Temperature;
import com.mbientlab.metawear.module.HumidityBme280;
import com.mbientlab.metawear.module.Timer;

import java.util.Objects;

import bolts.CancellationTokenSource;
import bolts.Continuation;
import bolts.Task;

public class DeviceSetupActivityFragment extends Fragment implements ServiceConnection {
    private Accelerometer accelerometer;
    private MetaWearBoard metawear = null;

    private FragmentSettings settings;



    public DeviceSetupActivityFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Activity owner = getActivity();
        if (!(owner instanceof FragmentSettings)) {
            throw new ClassCastException("Owning activity must implement the FragmentSettings interface");
        }

        settings = (FragmentSettings) owner;
        owner.getApplicationContext().bindService(new Intent(owner, BtleService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        ///< Unbind the service when the activity is destroyed
        Objects.requireNonNull(getActivity()).getApplicationContext().unbindService(this);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        setRetainInstance(true);
        return inflater.inflate(R.layout.fragment_device_setup, container, false);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        metawear = ((BtleService.LocalBinder) service).getMetaWearBoard(settings.getBtDevice());


    }

    @Override
    public void onServiceDisconnected(ComponentName name) {

    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.acc_start).setOnClickListener(v -> {

            accelerometer = metawear.getModule(Accelerometer.class);
            accelerometer.configure()
                    .odr(60f).commit();
            //logging.start(true);
            Log.i("Accel", "Accel start");
            accelerometer.acceleration().addRouteAsync(source ->
                    source.map(Function1.RSS).average((byte) 4).filter(ThresholdOutput.BINARY, 0.5f)
                            .multicast().to().filter(Comparison.EQ, -1).stream((Subscriber) (data, env) ->

                            Log.i("Accel", "in free fall"))
                            .to().filter(Comparison.EQ, 1).stream((Subscriber) (data, env) ->

                            Log.i("Accel", "no free fall"))
                            .end()).continueWith((Continuation<Route, Void>) task -> {
                accelerometer.acceleration().start();
                accelerometer.start();
                return null;
            });

        });

        view.findViewById(R.id.temp_start).setOnClickListener(v -> {
            final Temperature temperature = metawear.getModule(Temperature.class);
            final Temperature.Sensor tempSensor = temperature.findSensors(Temperature.SensorType.PRESET_THERMISTOR)[0];

            ((Temperature.ExternalThermistor) temperature.findSensors(Temperature.SensorType.EXT_THERMISTOR)[0])
                    .configure((byte) 0, (byte) 1, false);

            Log.i("Temp", "Temp start");

            metawear.getModule(BarometerBosch.class).start();
            temperature.findSensors(Temperature.SensorType.BOSCH_ENV)[0].read();


            tempSensor.addRouteAsync(source -> source.stream((Subscriber) (data, env) ->
            {
                Log.i("Temp", "Temperature (C) = " + data.value(Float.class));
            }))
                    .continueWith((Continuation<Route, Void>) task -> {
                        tempSensor.read();
                        return null;
                    });

        });

        view.findViewById(R.id.gryo_start).setOnClickListener(v -> {
            final GyroBmi160 gyroBmi160 = metawear.getModule(GyroBmi160.class);
            gyroBmi160.configure()
                    .odr(GyroBmi160.OutputDataRate.ODR_3200_HZ)
                    .range(GyroBmi160.Range.FSR_2000)
                    .commit();
            gyroBmi160.angularVelocity().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) ->
                    {
                        Log.i("Gyro", data.value(AngularVelocity.class).toString());
                    })).continueWith((Continuation<Route, Void>) task -> {
                gyroBmi160.angularVelocity();
                gyroBmi160.start();
                return null;
            });
        });

        view.findViewById(R.id.baro_start).setOnClickListener(v -> {

                    BarometerBosch baroBosch = metawear.getModule(BarometerBosch.class);

                    baroBosch.configure()
                            .filterCoeff(BarometerBosch.FilterCoeff.AVG_2)
                            .pressureOversampling(BarometerBosch.OversamplingMode.ULTRA_HIGH)
                            .standbyTime(3000.0f)
                            .commit();


                    baroBosch.pressure().addRouteAsync(source ->
                            source.stream((Subscriber) (data, env) ->
                            {
                                Log.i("Barometer", "Pressure (Pa) = " + data.value(Float.class));

                            })).continueWith((Continuation<Route, Void>) task -> {
                        baroBosch.start();
                        return null;

                    });
            baroBosch.altitude().addRouteAsync(source -> source.stream(
                    (Subscriber) (data, env) -> {

                Log.i("Altitude", "Altitude (m) = " + data.value(Float.class));
            })).continueWith((Continuation<Route, Void>) task -> {
                baroBosch.altitude().start();
                baroBosch.start();
                return null;
            });


                });

        view.findViewById(R.id.ambi_start).setOnClickListener(v -> {


            final AmbientLightLtr329 alsLtr329 = metawear.getModule(AmbientLightLtr329.class);
            alsLtr329.configure()
                    .gain(AmbientLightLtr329.Gain.LTR329_8X)
                    .integrationTime(AmbientLightLtr329.IntegrationTime.LTR329_TIME_250MS)
                    .measurementRate(AmbientLightLtr329.MeasurementRate.LTR329_RATE_2000MS)
                    .commit();

            alsLtr329.illuminance().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) -> Log.i("Ambi", "illuminance = %.3f lx" + data.value(Float.class))))
                    .continueWith((Continuation<Route, Void>) task -> {
                        alsLtr329.illuminance().start();
                        return null;
                    });
        });

        view.findViewById(R.id.magnetometer_start).setOnClickListener(v -> {

            final MagnetometerBmm150 magnetometer = metawear.getModule(MagnetometerBmm150.class);

            magnetometer.usePreset(MagnetometerBmm150.Preset.REGULAR);

            magnetometer.magneticField().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) ->
                            Log.i("Magnetic", data.value(MagneticField.class).toString())))
                    .continueWith((Continuation<Route, Void>) task -> {
                        magnetometer.magneticField().start();
                        magnetometer.start();
                        return null;
                    });
        });

        view.findViewById(R.id.sensor_fusion_quaternion_start).setOnClickListener(v -> {


            final SensorFusionBosch sensorFusion = metawear.getModule(SensorFusionBosch.class);
            final CancellationTokenSource cts = new CancellationTokenSource();


            sensorFusion.configure()
                    .mode(SensorFusionBosch.Mode.NDOF)
                    .accRange(SensorFusionBosch.AccRange.AR_16G)
                    .gyroRange(SensorFusionBosch.GyroRange.GR_2000DPS)

                    .commit();


            sensorFusion.eulerAngles().addRouteAsync(source -> source.stream((Subscriber) (data, env) ->
            {
                Log.i("Euler", "Euler Angle = " + data.value(EulerAngles.class));
            })).continueWith((Continuation<Route, Void>) task ->
            {
                sensorFusion.eulerAngles().start();
                sensorFusion.start();
                return null;
            });


            sensorFusion.quaternion().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) ->
                    {
                        Log.i("Quaternion", "Quaternion = " + data.value(Quaternion.class));
                    })).continueWith((Continuation<Route, Void>) task ->
            {
                sensorFusion.quaternion().start();
                sensorFusion.start();
                return null;
            });

        });

        view.findViewById(R.id.sensor_fusion_linear_acceleration_start).setOnClickListener(v -> {

            final SensorFusionBosch sensorFusion = metawear.getModule(SensorFusionBosch.class);
            final CancellationTokenSource cts = new CancellationTokenSource();


            sensorFusion.configure()
                    .mode(SensorFusionBosch.Mode.NDOF)
                    .accRange(SensorFusionBosch.AccRange.AR_16G)
                    .gyroRange(SensorFusionBosch.GyroRange.GR_2000DPS)
                    .commit();

            sensorFusion.linearAcceleration().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) ->
                    {
                        Log.i("Place", "Not sure what this is supposed to do." + data.value(Acceleration.class));

                    })).continueWith((Continuation<Route, Void>) task ->

            {
                sensorFusion.linearAcceleration().start();
                sensorFusion.start();
                return null;
            });


        });

        view.findViewById(R.id.settings).setOnClickListener(v -> {

            final Settings settings = metawear.getModule(Settings.class);

            settings.editBleAdConfig()
                    .deviceName("Travis Doesn't Do anything.")
                    .txPower((byte) -4)
                    .interval((short) 1024)
                    .timeout((byte) 100)
                    .commit();


            settings.editBleConnParams()
                    .minConnectionInterval(100f)
                    .maxConnectionInterval(1024f)
                    .commit();

            settings.battery().addRouteAsync(source ->
                    source.stream((Subscriber) (data, env) ->
                    {
                        Log.i("Settings", "battery state = " + data.value(Settings.BatteryState.class));
                    })).continueWith((Continuation<Route, Void>) task ->
            {
                settings.battery().read();
                return null;
            });

            settings.readCurrentPowerStatusAsync().continueWith((Continuation<Byte, Void>) task -> {

                Byte getBatteryConnectionStatus = task.getResult();
                String output;
                if (getBatteryConnectionStatus == 1) {

                    output = "Connected to power";

                } else {
                    output = "Disconnected from power";
                }
                Log.i("Settings", output);
                return null;

            });

        });


        view.findViewById(R.id.acc_stop).setOnClickListener(v -> {
            Log.i("Device", "stop");
            accelerometer.stop();
            accelerometer.acceleration().stop();
            metawear.tearDown();
        });


    }

    /**
     * Called when the app has reconnected to the board
     */
    public void reconnected() {

    }

    public interface FragmentSettings {
        BluetoothDevice getBtDevice();
    }
}


