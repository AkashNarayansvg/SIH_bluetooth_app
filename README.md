# SIH_bluetooth_app
Real time data receiving and fft spectrum analysing of data received from arduino using bluetooth with firebase backend

This App implements RFCOMM connection to the well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB
For an overview on Android Bluetooth communication see Android Bluetooth Overview.

This app receives data from arduino using hc05/hc06 bluetooth module in real time and calculates and plots the fast fourier transform of
data received . 

It computes and displays maximum amplitude and maximum frequency from every 64 samples of data received .

It uses realtime firebase backend to store the max amplitude and max frequency of data received for further reference.  

