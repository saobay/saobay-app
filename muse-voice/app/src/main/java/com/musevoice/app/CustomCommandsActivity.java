package com.musevoice.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Man hinh "Tao lenh moi": nguoi dung tu dinh nghia lenh noi -> hanh dong.
 * Lenh luu tren may (CommandsStore) va MainActivity nap lai moi khi quay ve.
 */
public class CustomCommandsActivity extends Activity {

    // Nhan hien thi <-> ma hanh dong (theo dung thu tu)
    private static final String[] ACTION_LABELS = {
            "Mở trang web (điền URL ở ô nội dung)",
            "Gửi sẵn 1 câu cho Muse (điền nội dung)",
            "Đọc to 1 câu (điền nội dung)",
            "Tắt màn hình",
            "Bật đèn pin",
            "Tắt đèn pin",
            "Tăng âm lượng",
            "Giảm âm lượng"
    };
    private static final String[] ACTION_CODES = {
            "url", "send", "speak", "lock", "torch_on", "torch_off", "vol_up", "vol_down"
    };

    private ArrayList<CommandsStore.Cmd> commands;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_commands);

        EditText etPhrase = findViewById(R.id.etPhrase);
        EditText etParam = findViewById(R.id.etParam);
        Spinner spAction = findViewById(R.id.spAction);
        ListView lv = findViewById(R.id.lvCommands);

        spAction.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ACTION_LABELS));

        commands = CommandsStore.load(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        lv.setAdapter(adapter);
        refreshList();

        // Cham vao 1 lenh -> hoi xoa
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            CommandsStore.Cmd c = commands.get(pos);
            new AlertDialog.Builder(this)
                    .setTitle("Xóa lệnh này?")
                    .setMessage("“" + c.phrase + "” → " + c.actionLabel()
                            + (c.param.isEmpty() ? "" : ": " + c.param))
                    .setPositiveButton("Xóa", (d, w) -> {
                        commands.remove(pos);
                        CommandsStore.save(this, commands);
                        refreshList();
                        Toast.makeText(this, "Đã xóa lệnh.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Giữ lại", null)
                    .show();
        });

        findViewById(R.id.btnSaveCmd).setOnClickListener(v -> {
            String phrase = etPhrase.getText().toString().trim();
            String param = etParam.getText().toString().trim();
            String action = ACTION_CODES[spAction.getSelectedItemPosition()];

            if (phrase.isEmpty()) {
                Toast.makeText(this, "Hãy nhập câu lệnh bạn sẽ nói.", Toast.LENGTH_LONG).show();
                return;
            }
            boolean needsParam = action.equals("url") || action.equals("send") || action.equals("speak");
            if (needsParam && param.isEmpty()) {
                Toast.makeText(this, "Hành động này cần ô nội dung (URL hoặc câu văn).", Toast.LENGTH_LONG).show();
                return;
            }
            // Trung cau lenh cu -> ghi de
            String norm = CommandsStore.stripAccents(phrase.toLowerCase()).trim();
            for (int i = commands.size() - 1; i >= 0; i--) {
                if (commands.get(i).phraseNorm().equals(norm)) commands.remove(i);
            }
            commands.add(new CommandsStore.Cmd(phrase, action, param));
            CommandsStore.save(this, commands);
            refreshList();
            etPhrase.setText("");
            etParam.setText("");
            Toast.makeText(this, "Đã lưu lệnh “" + phrase + "”. Về màn hình chính là dùng được ngay.", Toast.LENGTH_LONG).show();
        });
    }

    private void refreshList() {
        adapter.clear();
        if (commands.isEmpty()) {
            adapter.add("(chưa có lệnh nào — tạo lệnh đầu tiên ở trên)");
        } else {
            for (CommandsStore.Cmd c : commands) {
                adapter.add("🗣 “" + c.phrase + "”  →  " + c.actionLabel()
                        + (c.param.isEmpty() ? "" : ": " + c.param));
            }
        }
        adapter.notifyDataSetChanged();
    }
}
