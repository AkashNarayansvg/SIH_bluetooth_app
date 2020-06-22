package com.example.iproject;


import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IFillFormatter;
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.utils.ColorTemplate;
import com.github.mikephil.charting.utils.Utils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import org.jtransforms.fft.DoubleFFT_1D;

import java.util.ArrayList;
import java.util.Objects;

public class TerminalFragment extends Fragment implements SerialListener, ServiceConnection {


    private enum Connected { False, Pending, True }
    private static final String TAG = TerminalFragment.class.getSimpleName();

    private String deviceAddress;
    private String newline = "\r\n";

    private TextView receiveText,tv1,tv2,sendText;
    private EditText et;

    private SerialSocket socket;
    private SerialService service;
    private boolean initialStart = true;
    private Connected connected = Connected.False;

    double[] sig= new double[64];
    double[] fftData = new double[128];
    double[] res= new double[64];
    ArrayList<Entry> arrayList = new ArrayList<>();
    private  StringBuilder recDataString = new StringBuilder();
    private LineChart mchart;
    int count = 0;

    private int MaxAmp=0 ,MaxFreq=1,tmp=0;
    FirebaseDatabase database = FirebaseDatabase.getInstance();

    private  String dev_class="";
    DoubleFFT_1D fft = new DoubleFFT_1D(64);








    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        Log.i(TAG, "onCreate: ");
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
        dev_class=getArguments().getString("dev_name");
    }



    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy: ");
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        Log.i(TAG, "onStart: ");
        super.onStart();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        Log.i(TAG, "onStop: ");
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(Activity activity) {
        Log.i(TAG, "onAttach: ");
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        Log.i(TAG, "onDetach: ");
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }



    @Override
    public void onResume() {
        super.onResume();
        Log.i(TAG, "onResume: ");

        if(initialStart && service !=null) {
            initialStart = false;
            Objects.requireNonNull(getActivity()).runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.i(TAG, "onServiceConnected: ");
        service = ((SerialService.SerialBinder) binder).getService();
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.i(TAG, "onServiceDisconnected: ");
        service = null;
    }


    /**
     * UI
     * @param
     */

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.i(TAG, "onCreateView: ");
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        mchart= view.findViewById(R.id.lineChart);
        sendText = view.findViewById(R.id.send_text);
        //  View sendBtn = view.findViewById(R.id.send_btn);
        // sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));


        tv1=view.findViewById(R.id.tv1);
        tv2= view.findViewById(R.id.tv2);

        mchart.getDescription().setEnabled(false);
        mchart.setTouchEnabled(true);
        mchart.setDragEnabled(true);
        mchart.setScaleEnabled(true);

        mchart.setDrawGridBackground(false);
        mchart.setPinchZoom(true);
        mchart.setBackgroundColor(Color.LTGRAY);

        LineData data = new LineData();
        data.setValueTextColor(Color.WHITE);
        data.setHighlightEnabled(true);
        mchart.setData(data);



        // mchart.setVisibleXRangeMaximum(70f);

        Legend l = mchart.getLegend();
        l.setForm(Legend.LegendForm.LINE);


        XAxis x1 = mchart.getXAxis();
        x1.setTextColor(Color.WHITE);
        x1.setDrawGridLines(false);
        x1.setAvoidFirstLastClipping(true);
        x1.getSpaceMax();
        x1.enableGridDashedLine(10f, 10f, 0f);


        YAxis y1 = mchart.getAxisLeft();
        y1.setTextColor(Color.WHITE);
        y1.setAxisMinimum(0f);
        y1.setAxisMaximum(600f);
        y1.setDrawGridLines(true);
        YAxis y2 = mchart.getAxisRight();
        y2.setEnabled(false);

        {   // // Create Limit Lines // //
            LimitLine llXAxis = new LimitLine(9f, "Index 10");
            llXAxis.setLineWidth(4f);
            llXAxis.enableDashedLine(10f, 10f, 0f);
            llXAxis.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_BOTTOM);
            llXAxis.setTextSize(10f);
            //  llXAxis.setTypeface(tfRegular);


            LimitLine ll1 = new LimitLine(150f, "Critical Limit");
            ll1.setLineWidth(4f);
            ll1.enableDashedLine(10f, 10f, 0f);
            ll1.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_TOP);
            ll1.setTextSize(10f);
            //  ll1.setTypeface(tfRegular);


            LimitLine ll2 = new LimitLine( 15f, "Safe Limit");
            ll2.setLineWidth(4f);
            ll2.enableDashedLine(10f, 10f, 0f);
            ll2.setLabelPosition(LimitLine.LimitLabelPosition.RIGHT_BOTTOM);
            ll2.setTextSize(10f);
            //   ll2.setTypeface(tfRegular);


            // draw limit lines behind data instead of on top
            y1.setDrawLimitLinesBehindData(true);
            x1.setDrawLimitLinesBehindData(true);

            // add limit lines
            y1.addLimitLine(ll1);
            y1.addLimitLine(ll2);

        }

        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        }
        /* else if (id ==R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }*/
        else if(id==R.id.newline)
            System.exit(0);
        return false;
    }

    /**
     * Serial +UI
     * @param
     */

    private void connect()
    {
        Log.i(TAG, "connect: ");
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            String deviceName = device.getName() != null ? device.getName() : device.getAddress();
            status("connecting...");
            connected = Connected.Pending;
            socket = new SerialSocket();
            service.connect(this, "Connected to " + deviceName);
            socket.connect(getContext(), service, device);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }
    private  void disconnect()
    {
        Log.i(TAG, "disconnect: ");
        connected = Connected.False;
        service.disconnect();
        socket.disconnect();
        socket = null;
    }

    private void receive(byte[] data) {
        receiveText.append(new String(data));
        recDataString.append(new String(data));

/*
//        int k2= recDataString.indexOf("@"); if(k2>0) {
//            Log.i(TAG, "receive: Max amp:-"+ recDataString.substring(0,k2));
//        //    tv1.setText(""+recDataString.substring(0,k2)+"\n"); recDataString.delete(0,k2+1);
//        }
//        int k3= recDataString.indexOf("&"); if(k3>0)
//        {
//            Log.i(TAG, "receive: Max Freq"+ recDataString.substring(0,k3));
//            tv2.setText(""+recDataString.substring(0,k3)+"\n"); recDataString.delete(0,k3+1);
//        }
*/
        Log.i(TAG, "receive: data="+new String(data));
        int k = recDataString.indexOf("#");
        Log.i(TAG, "receive: recDataString= "+recDataString);
        if (k >0 ) {
            Log.i(TAG, "receive: "+ recDataString);
            Log.i(TAG, "receive: k="+k);
            String x = recDataString.substring(0, k);

            int t = Integer.valueOf(x);

            if(count<64)
            {sig[count]=t;
                if(t>MaxAmp)
                    MaxAmp=t;
            }


            count = count + 1;
            Log.i(TAG, "receive: cnt "+count);
            if (count == 64) {
                Model model = new Model();
//                Model2 model2= new Model2();
//                Model3 model3= new Model3();
//                Model4 model4= new Model4();
                Log.i(TAG, "receive: count is 64 graph plot begin");
                for(int i=0;i<64;i++)
                {
                    fftData[2*i]=sig[i];
                    fftData[2*i+1]= 0;
                }
                fft.realForwardFull(fftData);

                for(int i=0;i<64;i++)
                {
                    int c= (int)Math.ceil(Math.sqrt(fftData[2*i]*fftData[2*i] +fftData[2*i+1]*fftData[2*i+1]));
                    if(c>500)
                        c=500;
                    if(c>tmp && c!=500) {
                        tmp=c;
                        MaxFreq = (1000 * i) / 64;
                    }

                    arrayList.add(new Entry(i,c));
                }
                tv1.setText(""+MaxAmp);
                tv2.setText(""+MaxFreq);
                Log.i("wahan", "receive"+dev_class);

                    final DatabaseReference table_user= database.getReference();
                    model.setMaxAmp(MaxAmp);
                    model.setMaxFreq(MaxFreq);
                    table_user.push().setValue(model);
//                else if(dev_class.equalsIgnoreCase("Model2")){
//                    tv1.setText(dev_class);
//                    final DatabaseReference table_user= database.getReference("Model2");
//                    model1.setMaxAmp(MaxAmp);
//                    model1.setMaxFreq(MaxFreq);
//                    table_user.push().setValue(model2);
//                }
//                else if(dev_class.equalsIgnoreCase("Model3")){
//                    final DatabaseReference table_user= database.getReference("Model3");
//                    model3.setMaxAmp(MaxAmp);
//                    model3.setMaxFreq(MaxFreq);
//                    table_user.push().setValue(model3);
//                }
//                else
//                {
//                    final DatabaseReference table_user= database.getReference("Model4");
//                    model4.setMaxAmp(MaxAmp);
//                    model4.setMaxFreq(MaxFreq);
//                    table_user.push().setValue(model4);
//
//                }


                MaxAmp=0;
                MaxFreq=1;
                tmp=0;

                Log.i(TAG, "runtrew "+count);
                Log.d(TAG, "runtrew "+arrayList);
                count=0;
                //  hyy();
                MyClass tw= new MyClass(arrayList);
                tw.start();
                Log.i(TAG, "runtrew "+" I m outta here");
                arrayList.clear();
                mchart.invalidate();

            }
            //  Log.d(TAG, "receive: "+count);
            recDataString.delete(0, k + 1);

        }
        if(recDataString.length()>0)
            if(recDataString.charAt(0)=='#')
            {
                Log.i(TAG, "receive: index at 0 is #");
                recDataString.delete(recDataString.indexOf("#"),recDataString.indexOf("#")+1);
            }


    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
        //sendText.setText(spn);

    }

    /**
     * Serial Listener
     *
     */
    @Override
    public void onSerialConnect() {
        Log.i(TAG, "onSerialConnect: ");
        status("connected");
        connected = Connected.True;
    }


    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();

    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }
    private void addEntry(ArrayList<Entry> values)
    {
        Log.i(TAG, "addEntry: ");
        Log.i("hhaa", "addEntry: here ");
        LineDataSet set1;
        if(mchart.getData()!=null && mchart.getData().getDataSetCount()>0)
        {
            Log.i("hhaa", "addEntry: here 3 ");
            set1=(LineDataSet) mchart.getData().getDataSetByIndex(0);
            set1.setValues(values);
            set1.notifyDataSetChanged();
            mchart.getData().notifyDataChanged();
            mchart.notifyDataSetChanged();
        }
        else
        {
            Log.i("hhaal", "addEntry: here 2");
            set1 = createSet(values);

            ArrayList<ILineDataSet> dataSets = new ArrayList<>();
            dataSets.add(set1);

            LineData data= new LineData(dataSets);
            mchart.setData(data);

            //mchart.clearDisappearingChildren();


        }
    }
    private  LineDataSet createSet(ArrayList<Entry> values)
    {
        Log.i(TAG, "createSet: ");
        LineDataSet set1= new LineDataSet(values,"");
        set1.setDrawCircles(true);
        set1.setAxisDependency(YAxis.AxisDependency.RIGHT);
        set1.setAxisDependency(YAxis.AxisDependency.LEFT);
        set1.setColor(ColorTemplate.getHoloBlue());
        set1.setCircleColor(ColorTemplate.getHoloBlue());
        set1.setLineWidth(2f);
        set1.setCircleRadius(4f);
        set1.setFillAlpha(65);
        set1.setFillColor(ColorTemplate.getHoloBlue());
        set1.setHighLightColor(Color.rgb(247, 117, 177));
        set1.setValueTextColor(Color.WHITE);
        set1.setValueTextSize(10f);

        set1.setDrawIcons(false);

        // draw dashed line
        set1.enableDashedLine(10f, 5f, 0f);

        // black lines and points
        set1.setColor(Color.BLACK);
        set1.setCircleColor(Color.BLACK);

        // line thickness and point size
        set1.setLineWidth(0.9f);
        set1.setCircleRadius(2f);

        // draw points as solid circles
        set1.setDrawCircleHole(false);

        // customize legend entry
        set1.setFormLineWidth(1f);
        set1.setFormLineDashEffect(new DashPathEffect(new float[]{10f, 5f}, 0f));
        set1.setFormSize(15.f);

        // text size of values
        //         set1.setValueTextSize(9f);

        // draw selection line as dashed
        //        set1.enableDashedHighlightLine(10f, 5f, 0f);

        // set the filled area
        set1.setDrawFilled(true);
        set1.setFillFormatter(new IFillFormatter() {
            @Override
            public float getFillLinePosition(ILineDataSet dataSet, LineDataProvider dataProvider) {
                Log.d("tah", "getFillLinePosition: " + mchart.getAxisLeft().getAxisMinimum());
                return mchart.getAxisLeft().getAxisMinimum();

            }
        });

        // set color of filled area
        if (Utils.getSDKInt() >= 18) {
            // drawables only supported on api level 18 and above
            Drawable drawable = ContextCompat.getDrawable(getActivity().getApplicationContext(), R.drawable.fade_red);
            set1.setFillDrawable(drawable);
        } else {
            set1.setFillColor(Color.BLACK);
        }

        return set1;



    }

    class MyClass extends Thread{
        ArrayList<Entry> arrayList ;
        MyClass(ArrayList<Entry> arrayList)
        {
            this.arrayList = new ArrayList<>(arrayList);
        }

        @Override
        public void run() {
            Log.i(TAG, "run: in MyClass "+arrayList);
            addEntry(arrayList);
        }

        private void addEntry(ArrayList<Entry> values)
        {
            Log.i(TAG, "addEntry: ");
            Log.i("hhaa", "addEntry: here ");
            LineDataSet set1;
            if(mchart.getData()!=null && mchart.getData().getDataSetCount()>0)
            {
                Log.i("hhaa", "addEntry: here 3 ");
                set1=(LineDataSet) mchart.getData().getDataSetByIndex(0);
                set1.setValues(values);
                set1.notifyDataSetChanged();
                mchart.getData().notifyDataChanged();
                mchart.notifyDataSetChanged();
            }
            else
            {
                Log.i("hhaal", "addEntry: here 2");
                set1 = createSet(values);

                ArrayList<ILineDataSet> dataSets = new ArrayList<>();
                dataSets.add(set1);

                LineData data= new LineData(dataSets);
                Handler handler= new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        mchart.setData(data);
                    }
                });


                //mchart.clearDisappearingChildren();


            }
        }
        private  LineDataSet createSet(ArrayList<Entry> values)
        {
            Log.i(TAG, "createSet: ");
            LineDataSet set1= new LineDataSet(values,"");
            set1.setDrawCircles(true);
            set1.setAxisDependency(YAxis.AxisDependency.RIGHT);
            set1.setAxisDependency(YAxis.AxisDependency.LEFT);
            set1.setColor(ColorTemplate.getHoloBlue());
            set1.setCircleColor(ColorTemplate.getHoloBlue());
            set1.setLineWidth(2f);
            set1.setCircleRadius(4f);
            set1.setFillAlpha(65);
            set1.setFillColor(ColorTemplate.getHoloBlue());
            set1.setHighLightColor(Color.rgb(247, 117, 177));
            set1.setValueTextColor(Color.WHITE);
            set1.setValueTextSize(10f);

            set1.setDrawIcons(false);

            // draw dashed line
            set1.enableDashedLine(10f, 5f, 0f);

            // black lines and points
            set1.setColor(Color.BLACK);
            set1.setCircleColor(Color.BLACK);

            // line thickness and point size
            set1.setLineWidth(0.9f);
            set1.setCircleRadius(2f);

            // draw points as solid circles
            set1.setDrawCircleHole(false);

            // customize legend entry
            set1.setFormLineWidth(1f);
            set1.setFormLineDashEffect(new DashPathEffect(new float[]{10f, 5f}, 0f));
            set1.setFormSize(15.f);

            // text size of values
            //         set1.setValueTextSize(9f);

            // draw selection line as dashed
            //        set1.enableDashedHighlightLine(10f, 5f, 0f);

            // set the filled area
            set1.setDrawFilled(true);
            set1.setFillFormatter(new IFillFormatter() {
                @Override
                public float getFillLinePosition(ILineDataSet dataSet, LineDataProvider dataProvider) {
                    Log.d("tah", "getFillLinePosition: " + mchart.getAxisLeft().getAxisMinimum());
                    return mchart.getAxisLeft().getAxisMinimum();

                }
            });

            // set color of filled area
            if (Utils.getSDKInt() >= 18) {
                // drawables only supported on api level 18 and above
                Drawable drawable = ContextCompat.getDrawable(getActivity().getApplicationContext(), R.drawable.fade_red);
                set1.setFillDrawable(drawable);
            } else {
                set1.setFillColor(Color.BLACK);
            }

            return set1;



        }

    }
}
