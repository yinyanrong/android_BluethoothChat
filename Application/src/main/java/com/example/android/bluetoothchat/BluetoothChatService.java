/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.bluetoothchat;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.example.android.common.logger.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 这个类所做的工作是设置管理蓝牙连接的设备，
 * 一个线程监听来自其他设备的请求连接，
 * 一个线程负责连接一个设备，
 * 一个线程执行连接后的数据交换
 */
public class BluetoothChatService {
    // Debugging
    private static final String TAG = "BluetoothChatService";

    // 这个名字为了创建了server socket 的时候SDP 记录所用到的
    //http://blog.chinaunix.net/uid-23193900-id-3278983.html 协议的介绍
    private static final String NAME_SECURE = "BluetoothChatSecure";
    private static final String NAME_INSECURE = "BluetoothChatInsecure";

    // 这个应用的唯一的  UUID
    private static final UUID MY_UUID_SECURE =
            UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");
    private static final UUID MY_UUID_INSECURE =
            UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66");


    private final BluetoothAdapter mAdapter;
    private final Handler mHandler;
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // 当前链接的状态
    public static final int STATE_NONE = 0;       // 什么都没做
    public static final int STATE_LISTEN = 1;     // 一个正要进入的链接
    public static final int STATE_CONNECTING = 2; // 一个正要断开的连接
    public static final int STATE_CONNECTED = 3;  // 链接到一个远程的设备

    /**
     * 准备一个新的 蓝牙会话的 session.
     * 更新 UI 所使用的Handler
     */
    public BluetoothChatService(Context context, Handler handler) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mHandler = handler;
    }

    /**
     * 设置一个当前连接的状态
     *  定义了一个整数类型作为当前当前连接的状态
     */
    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;

        // 给予 Handler 一个新的状态来让 UI Activity 更新
        mHandler.obtainMessage(Constants.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

     // 返回当前连接的状态
    public synchronized int getState() {
        return mState;
    }


    //******************************  被动接受连接（服务器）的准备操作********************************************
    /**
     * 开启这个聊天服务. 特别指定 AcceptThread 作为一个Session的监听模型
     *  这个方法在 Activity onResume() 被调用
     */
    public synchronized void start() {
        Log.d(TAG, "start");

        // 停止连接中的线程
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // 停止已经连接的线程
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        setState(STATE_LISTEN);

        // 开启线程去监听 BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }

    /**
     * 当连接成功之后这个线程运行. 它的特点是就像一个服务器站点的客户端，
     * 它连接的过程中一直等待接受数据直到用户取消
     */
    private class AcceptThread extends Thread {

        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // 创建一个Socket 服务器监听器
            try {
                if (secure) {
                    // 安全的连接
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
                            MY_UUID_SECURE);

                } else {
                    //不安全的连接（蓝牙2.1之前的设备没有加密）
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
                            NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "连接类型 Type: " + mSocketType + "AcceptThread监听  错误：", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            Log.d(TAG, "Socket Type: " + mSocketType +
                    "开始 mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;

            // 如果没有连接就监听服务端的socket
            while (mState != STATE_CONNECTED) {
                try {
                    // 这是一个阻塞调用,只会返回成功或者抛出一个异常
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "Socket Type: " + mSocketType + "accept() failed", e);
                    break;
                }

                // 一个连接被接收了
                if (socket != null) {
                    synchronized (BluetoothChatService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // 正常情况下会发起一个连接
                                connected(socket, socket.getRemoteDevice(),
                                        mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // 另外一种情况 ，如果没有准备好或者已经有一个连接，则终止新的这个新的socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    Log.e(TAG, "不能关闭没有必要的连接", e);
                                }
                                break;
                        }
                    }
                }
            }
            Log.i(TAG, "结束 mAcceptThread, socket Type: " + mSocketType);

        }

        public void cancel() {
            Log.d(TAG, "Socket Type" + mSocketType + "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Socket Type" + mSocketType + "服务端的socket关闭错误：", e);
            }
        }
    }


    /**
     * 开启 ConnectedThread 线程来管理一个蓝牙的连接
     *
     * @param socket  BluetoothSocket 连接生成
     * @param device  BluetoothDevice 已经连接的设备信息
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice
            device, final String socketType) {
        Log.d(TAG, "connected, Socket Type:" + socketType);

        // 取消这个已经完成的连接
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // 取消当前运行的连接
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // 停止接收的线程因为当前我们只想连接一个设备
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }

        // 启动一个新的线程来管理数据的传输
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();

        // 发送这个连接蓝牙的设备的名字到 UI Activity中
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_DEVICE_NAME);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.DEVICE_NAME, device.getName());
        msg.setData(bundle);
        mHandler.sendMessage(msg);

        setState(STATE_CONNECTED);
    }
    /**
     * 当有一个远程的设备连接的时候这个线程会运行
     * 它处理所有传入与发送的信息
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            Log.d(TAG, "create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // 通过 BluetoothSocket 获取输入与输出流
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "临时的 sockets 没有创建", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "开始 mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // 当连接之后保持输入流的监听
            while (mState == STATE_CONNECTED) {
                try {
                    // 读取这个 InputStream
                    bytes = mmInStream.read(buffer);

                    // 发送数据通过UI得到的byte[]字节数组
                    mHandler.obtainMessage(Constants.MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    // 重新启动服务的监听模式
                    BluetoothChatService.this.start();
                    break;
                }
            }
        }

        /**
         *通过OutStream.输出数据
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);

                // 把消息发送给UI更新Activity
                mHandler.obtainMessage(Constants.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "输出数据时出错！", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "socket 关闭出错", e);
            }
        }
    }
    //*********************************END*******************************************



    //******************************* 主动发起连接（客户端）******************************************
    /**
     * 开始连接一个远程的蓝牙设备.
     *  连接的安全类型- Secure (true) , Insecure (false)
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        Log.d(TAG, "connect to: " + device);

        // 停止试图连接的线程
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // 停止正在连接的线程
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // 连接一个蓝牙设备
        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }


    /**
     * 这个线程 的运行试图连接一个外部的蓝牙设备，
     * 这个线程一直会持续到这个连接返回成功或者失败
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // 从BluetoothDevice  连接中获取 BluetoothSocket 对象
            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
                            MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
                            MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                Log.e(TAG, "Socket Type: " + mSocketType + "创建 BluetoothSocket   失败", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.i(TAG, "开始 mConnectThread SocketType:" + mSocketType);
            setName("ConnectThread" + mSocketType);

            // 这里经常会使用 discovery方法 因为在连接的过程中正在进行发现操作，
            // 则会大幅降低连接尝试的速度，并增加连接失败的可能性
            mAdapter.cancelDiscovery();

            // 建立连接
            try {
                // 这是一个阻塞试的回调，它会返回成功或者一个抛出一个异常
                mmSocket.connect();
            } catch (IOException e) {
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "BluetoothSocket 关闭异常 " + mSocketType +
                            " socket 连接时出错", e2);
                }
                connectionFailed();
                e.printStackTrace();
                return;
            }

            // 把当前的对象置null因为已经使用完了
            synchronized (BluetoothChatService.this) {
                mConnectThread = null;
            }

            // 开启已连接的线程
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "关闭ConnectThread 对象 " + mSocketType + " socket 出错", e);
            }
        }
    }

    /**
     * 停止所有的线程
     */
    public synchronized void stop() {
        Log.d(TAG, "stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        setState(STATE_NONE);
    }


     //通过ConnectedThread 线程把数据传送给另一台蓝牙设备
    public void write(byte[] out) {
        // 创建临时对象
        ConnectedThread r;
        // 同步拷贝 ConnectedThread  对象
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // 执行同步写出
        r.write(out);
    }


     //初始化连接蓝牙设备时的错误 并通知UI更新
    private void connectionFailed() {
        // 发送这个错误的消息给UI
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "未知的连接设备");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        // 从新启动BluetoothChatService的监听模式
        BluetoothChatService.this.start();
    }


     //初始化连接丢失的信息并通知UI更新
    private void connectionLost() {
        // 发送错误的信息到UI
        Message msg = mHandler.obtainMessage(Constants.MESSAGE_TOAST);
        Bundle bundle = new Bundle();
        bundle.putString(Constants.TOAST, "蓝牙设备连接丢失");
        msg.setData(bundle);
        mHandler.sendMessage(msg);
        // 从新启动BluetoothChatService的监听模式
        BluetoothChatService.this.start();
    }

}
