package com.cherrydev.airsend.app.connections;

import static com.cherrydev.airsend.app.MyApplication.databaseManager;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.cherrydev.airsend.R;
import com.cherrydev.airsend.app.AppViewModel;
import com.cherrydev.airsend.app.database.models.Device;
import com.cherrydev.airsend.app.service.ServerService;
import com.cherrydev.airsend.app.utils.BarcodeUtils;
import com.cherrydev.airsend.app.utils.ClipboardUtils;
import com.cherrydev.airsend.app.utils.DialogRecyclerViewAction;
import com.cherrydev.airsend.app.utils.NetworkUtils;
import com.cherrydev.airsend.databinding.FragmentConnectionsBinding;
import com.cherrydev.airsendcore.core.client.ClientManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;

@SuppressLint("SetTextI18n")
public class FragmentConnections extends Fragment {

    private AppViewModel viewModel;
    private FragmentConnectionsBinding binding;
    private AdapterDevice adapter;
    private BottomSheetBehavior bottomSheetBehavior;


    public static FragmentConnections newInstance() {
        return new FragmentConnections();
    }


    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentConnectionsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);


        viewModel = new ViewModelProvider(requireActivity()).get(AppViewModel.class);


        initBottomSheetDeviceInfo();


        AdapterDevice.OnClickListener clickListener = (device, clickItem) -> handleDeviceClick(device, clickItem);
        adapter = new AdapterDevice(clickListener);
        adapter.setHasStableIds(true);
        viewModel.getDevices().observe(getViewLifecycleOwner(), devices -> {
            List<DeviceWrapper> deviceWrapperList = new ArrayList<>();
            long id = 0;
            for (Device device : devices) {
                deviceWrapperList.add(new DeviceWrapper(id, device));
                id++;
            }

            adapter.updateData(deviceWrapperList);

            binding.tvEmptyRv.setVisibility(devices.size() == 0 ? View.VISIBLE : View.GONE);
            binding.recyclerView.setVisibility(devices.size() != 0 ? View.VISIBLE : View.GONE);
        });


        RecyclerView recyclerView = binding.recyclerView;
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);


        binding.btnStop.setOnClickListener(v -> ServerService.stopService());
        binding.btnStart.setOnClickListener(v -> initServer());


        binding.btnDisconnectAll.setOnClickListener(v -> ClientManager.getInstance().disconnectAll());
        binding.btnConnectAll.setOnClickListener(v -> {
            if (isNotConnected()) return;

            databaseManager.getDb().getAllDevices().subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread()).subscribe(devices -> {
                var pairs = devices.stream().map(p -> new Pair<>(p.getIP(), p.getPort())).collect(Collectors.toList());
                ClientManager.getInstance().connectToList(pairs);
            });
        });


        binding.fabConnect.setOnClickListener(v -> {
            if (isNotConnected()) return;

            DialogMakeConnection.DialogMakeConnectionListener listener = (IP, port) -> connectToClient(IP, port);
            DialogMakeConnection.newInstance(listener).show(getParentFragmentManager(), null);
        });
    }

    private void connectToClient(String IP, int port) {
        ClientManager client = ClientManager.getInstance();
        client.connect(IP, port);

        Toast.makeText(requireActivity(), requireContext().getString(R.string.message_sent), Toast.LENGTH_LONG).show();
    }


    private void handleDeviceClick(Device connectionItem, AdapterDevice.ClickItem clickItem) {
        String itemIP = connectionItem.getIP();
        int itemPort = connectionItem.getPort();


        DialogRecyclerViewAction.DialogButtonListener<DeviceActionWrapper> listener = item -> {
            switch (item.getDeviceAction()) {
                case CONNECT:
                    if (isNotConnected()) break;
                    ClientManager.getInstance().connect(itemIP, itemPort);
                    break;
                case DISCONNECT:
                    ClientManager.getInstance().disconnect(itemIP, itemPort);
                    break;
                case DELETE:
                    ClientManager.getInstance().deleteClient(itemIP, itemPort);
                    break;
            }
        };


        switch (clickItem) {
            case CLICK:
                ClipboardUtils.copyToClipboard(requireContext(), itemIP);
                Toast.makeText(requireContext(), requireContext().getString(R.string.copied_IP_to_clipboard), Toast.LENGTH_LONG).show();
                break;

            case LONG_CLICK: {
                DialogRecyclerViewAction<DeviceActionWrapper> dialog = DialogRecyclerViewAction.newInstance(getString(R.string.device_actions), getString(R.string.what_would_you_like_to_do_with_the_device), List.of(
                                new DeviceActionWrapper(DeviceAction.CONNECT, requireContext().getString(R.string.connect)),
                                new DeviceActionWrapper(DeviceAction.DISCONNECT, requireContext().getString(R.string.disconnect)),
                                new DeviceActionWrapper(DeviceAction.DELETE, requireContext().getString(R.string.delete))),
                        "", getString(R.string.cancel), listener);

                dialog.show(getParentFragmentManager(), null);

                break;
            }
        }
    }

    private void initBottomSheetDeviceInfo() {
        View bottomSheet = binding.bottomSheet;
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        bottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    showDeviceData();
                    binding.btnDeviceInfo.setIcon(getDrawable(R.drawable.ic_keyboard_arrow_down));
                } else {
                    binding.btnDeviceInfo.setIcon(getDrawable(R.drawable.arrow_up));
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {

            }
        });

        LinearLayout buttonReceive = binding.buttonReceive;
        buttonReceive.setOnClickListener(v -> showHideReceiveLayout());
    }


    private Drawable getDrawable(int id) {
        return requireContext().getDrawable(id);
    }


    private void showDeviceData() {
        ImageView myImage = binding.imageViewQr;

        NetworkUtils.printSupportedProtocols();
        var ip = NetworkUtils.getIPAddress(false);
        TextView ipTV = binding.ipTv;

        if (ServerService.getPORT() == 0 || ip.equals("null")) {
            ipTV.setText(getString(R.string.server_is_not_started));
            BarcodeUtils.getBitmap(getString(R.string.server_is_not_started)).observeOn(AndroidSchedulers.mainThread()).subscribe(bitmap -> myImage.setImageBitmap(bitmap));

        } else {
            ipTV.setText(ip + " | PORT: " + ServerService.getPORT());
            BarcodeUtils.getBitmap(ip + "," + ServerService.getPORT()).observeOn(AndroidSchedulers.mainThread()).subscribe(bitmap -> myImage.setImageBitmap(bitmap));
        }
    }


    private void initServer() {
        ServerService.startService();
    }


    private void showHideReceiveLayout() {
        int state = bottomSheetBehavior.getState();
        boolean curVisB = (state == BottomSheetBehavior.STATE_EXPANDED);
        bottomSheetBehavior.setState(curVisB ? BottomSheetBehavior.STATE_COLLAPSED : BottomSheetBehavior.STATE_EXPANDED);
    }


    private boolean isNotConnected() {
        if (!NetworkUtils.isConnectedToNetwork(requireContext())) {
            Toast.makeText(requireContext(), getString(R.string.not_connected_to_network), Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }
}