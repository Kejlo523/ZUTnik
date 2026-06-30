package pl.kejlo.zutnik;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class AddEditTileDialog extends DialogFragment {

    private Tile tileToEdit;
    private OnTileSavedListener listener;

    public interface OnTileSavedListener {
        void onTileSaved(Tile tile);
    }

    public static AddEditTileDialog newInstance(Tile tile) {
        AddEditTileDialog dialog = new AddEditTileDialog();
        dialog.tileToEdit = tile;
        return dialog;
    }

    public void setListener(OnTileSavedListener listener) {
        this.listener = listener;
    }

    private int selectedColor = 0; // 0 = Default

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        LayoutInflater inflater = requireActivity().getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_add_edit_tile, null);

        EditText etTitle = view.findViewById(R.id.etTitle);
        EditText etDesc = view.findViewById(R.id.etDesc);
        EditText etUrl = view.findViewById(R.id.etUrl);
        Spinner spinnerAction = view.findViewById(R.id.spinnerAction);
        Spinner spinnerSavedParams = view.findViewById(R.id.spinnerSavedParams);
        View containerUrl = view.findViewById(R.id.containerUrl);
        View containerSavedParams = view.findViewById(R.id.containerSavedParams);

        // Color UI
        android.widget.LinearLayout containerColors = view.findViewById(R.id.containerColors);
        View colorPreview = view.findViewById(R.id.colorPreview);
        TextView btnColorDefault = view.findViewById(R.id.btnColorDefault);
        SeekBar seekHue = view.findViewById(R.id.seekHue);
        SeekBar seekSat = view.findViewById(R.id.seekSat);
        SeekBar seekVal = view.findViewById(R.id.seekVal);

        float[] hsv = new float[] { 0f, 0f, 1f };
        int themeDefaultColor = ThemeManager.resolveColor(requireContext(), R.attr.mzCardSoft);
        android.graphics.drawable.GradientDrawable previewDrawable = new android.graphics.drawable.GradientDrawable();
        previewDrawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        previewDrawable.setStroke(dpToPx(1), ThemeManager.resolveColor(requireContext(), R.attr.mzBorderSoft));
        previewDrawable.setColor(themeDefaultColor);
        colorPreview.setBackground(previewDrawable);

        // Setup Colors
        int[] palette = {
                0, // Default
                0xFFFF5252, // Red
                0xFF448AFF, // Blue
                0xFF4CAF50, // Green
                0xFFFF9800, // Orange
                0xFF9C27B0, // Purple
                0xFF607D8B, // Blue Grey
                0xFF212121, // Dark
                0xFFE0E0E0 // Light
        };

        for (int color : palette) {
            View cView = new View(requireContext());
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(dpToPx(36),
                    dpToPx(36));
            lp.setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
            cView.setLayoutParams(lp);

            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (color == 0) {
                gd.setColor(0xFFFFFFFF); // White visual for default
                gd.setStroke(dpToPx(1), 0xFFCCCCCC); // Border
            } else {
                gd.setColor(color);
            }
            cView.setBackground(gd);

            cView.setOnClickListener(v -> {
                selectedColor = color;
                int applied = color == 0 ? themeDefaultColor : color;
                android.graphics.Color.colorToHSV(applied, hsv);
                seekHue.setProgress(Math.round(hsv[0]));
                seekSat.setProgress(Math.round(hsv[1] * 100f));
                seekVal.setProgress(Math.round(hsv[2] * 100f));
                previewDrawable.setColor(applied);
            });
            containerColors.addView(cView);
        }

        btnColorDefault.setOnClickListener(v -> {
            selectedColor = 0;
            android.graphics.Color.colorToHSV(themeDefaultColor, hsv);
            seekHue.setProgress(Math.round(hsv[0]));
            seekSat.setProgress(Math.round(hsv[1] * 100f));
            seekVal.setProgress(Math.round(hsv[2] * 100f));
            previewDrawable.setColor(themeDefaultColor);
        });

        SeekBar.OnSeekBarChangeListener hsvListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser)
                    return;
                hsv[0] = seekHue.getProgress();
                hsv[1] = seekSat.getProgress() / 100f;
                hsv[2] = seekVal.getProgress() / 100f;
                selectedColor = android.graphics.Color.HSVToColor(hsv);
                previewDrawable.setColor(selectedColor);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
        seekHue.setOnSeekBarChangeListener(hsvListener);
        seekSat.setOnSeekBarChangeListener(hsvListener);
        seekVal.setOnSeekBarChangeListener(hsvListener);

        // Populate Spinner
        List<String> actionLabels = new ArrayList<>();
        List<String> actionValues = new ArrayList<>();

        // Define actions
        actionLabels.add(getString(R.string.tile_action_plan));
        actionValues.add(Tile.ACTION_PLAN);
        actionLabels.add(getString(R.string.tile_action_grades));
        actionValues.add(Tile.ACTION_GRADES);
        actionLabels.add(getString(R.string.tile_action_info));
        actionValues.add(Tile.ACTION_INFO);
        actionLabels.add(getString(R.string.tile_action_news));
        actionValues.add(Tile.ACTION_NEWS);
        actionLabels.add(getString(R.string.tile_action_news_latest));
        actionValues.add(Tile.ACTION_NEWS_LATEST);
        actionLabels.add(getString(R.string.tile_action_plan_search));
        actionValues.add(Tile.ACTION_PLAN_SEARCH);
        actionLabels.add(getString(R.string.tile_action_useful));
        actionValues.add("useful"); // Mapped later
        actionLabels.add(getString(R.string.tile_action_about));
        actionValues.add("about");
        actionLabels.add(getString(R.string.tile_action_url));
        actionValues.add(Tile.ACTION_URL);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item_dark,
                actionLabels);
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerAction.setAdapter(adapter);

        // Load Saved Searches
        List<PlanRepository.SavedSearch> savedSearches = PlanRepository.loadSavedSearches(requireContext());
        List<String> savedLabels = new ArrayList<>();
        if (savedSearches.isEmpty()) {
            savedLabels.add(getString(R.string.dialog_add_edit_tile_spinner_empty));
        } else {
            for (PlanRepository.SavedSearch s : savedSearches) {
                savedLabels.add(s.label + " (" + s.catLabel + ": " + s.query + ")");
            }
        }
        ArrayAdapter<String> savedAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_item_dark,
                savedLabels);
        savedAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_dark);
        spinnerSavedParams.setAdapter(savedAdapter);

        spinnerAction.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                String val = actionValues.get(position);
                if (Tile.ACTION_URL.equals(val)) {
                    containerUrl.setVisibility(View.VISIBLE);
                    containerSavedParams.setVisibility(View.GONE);
                } else if (Tile.ACTION_PLAN_SEARCH.equals(val)) {
                    containerUrl.setVisibility(View.GONE);
                    containerSavedParams.setVisibility(View.VISIBLE);
                } else {
                    containerUrl.setVisibility(View.GONE);
                    containerSavedParams.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // Pre-fill if editing
        if (tileToEdit != null) {
            builder.setTitle(R.string.dialog_add_edit_tile_title_edit);

            // Resolve strings if resource IDs are present
            if (tileToEdit.titleResId != 0) {
                etTitle.setText(getString(tileToEdit.titleResId));
            } else {
                etTitle.setText(tileToEdit.title);
            }

            if (tileToEdit.descResId != 0) {
                etDesc.setText(getString(tileToEdit.descResId));
            } else {
                etDesc.setText(tileToEdit.description);
            }

            // ... (rest of setup) ...

            // Color pre-fill
            selectedColor = tileToEdit.color;
            int applied = selectedColor == 0 ? themeDefaultColor : selectedColor;
            android.graphics.Color.colorToHSV(applied, hsv);
            seekHue.setProgress(Math.round(hsv[0]));
            seekSat.setProgress(Math.round(hsv[1] * 100f));
            seekVal.setProgress(Math.round(hsv[2] * 100f));
            previewDrawable.setColor(applied);

            String currentType = tileToEdit.actionType;
            if (Tile.ACTION_ACTIVITY.equals(currentType) && tileToEdit.actionData != null) {
                if (tileToEdit.actionData.contains("UsefulLinksActivity"))
                    currentType = "useful";
                else if (tileToEdit.actionData.contains("AboutActivity"))
                    currentType = "about";
            }

            int selIndex = actionValues.indexOf(currentType);
            if (selIndex >= 0) {
                spinnerAction.setSelection(selIndex);
            }

            if (Tile.ACTION_URL.equals(currentType)) {
                containerUrl.setVisibility(View.VISIBLE);
                etUrl.setText(tileToEdit.actionData);
            } else if (Tile.ACTION_PLAN_SEARCH.equals(currentType)) {
                containerSavedParams.setVisibility(View.VISIBLE);
                // Try to match actionData to saved search
                try {
                    org.json.JSONObject o = new org.json.JSONObject(tileToEdit.actionData);
                    String key = o.optString("ck");
                    String q = o.optString("q");
                    for (int i = 0; i < savedSearches.size(); i++) {
                        PlanRepository.SavedSearch s = savedSearches.get(i);
                        if (s.catKey.equals(key) && s.query.equals(q)) {
                            spinnerSavedParams.setSelection(i);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        } else {
            builder.setTitle(R.string.dialog_add_edit_tile_title_new);
            android.graphics.Color.colorToHSV(themeDefaultColor, hsv);
            seekHue.setProgress(Math.round(hsv[0]));
            seekSat.setProgress(Math.round(hsv[1] * 100f));
            seekVal.setProgress(Math.round(hsv[2] * 100f));
            previewDrawable.setColor(themeDefaultColor);
        }

        builder.setView(view)
                .setPositiveButton(R.string.dialog_add_edit_tile_btn_save, (dialog, id) -> {
                    String title = etTitle.getText().toString();
                    String desc = etDesc.getText().toString();
                    int pos = spinnerAction.getSelectedItemPosition();
                    String type = actionValues.get(pos);
                    String data = null;

                    if (Tile.ACTION_URL.equals(type)) {
                        data = etUrl.getText().toString();
                    } else if (Tile.ACTION_PLAN_SEARCH.equals(type)) {
                        int savedPos = spinnerSavedParams.getSelectedItemPosition();
                        if (savedPos >= 0 && savedPos < savedSearches.size()) {
                            PlanRepository.SavedSearch s = savedSearches.get(savedPos);
                            try {
                                org.json.JSONObject o = new org.json.JSONObject();
                                o.put("ck", s.catKey);
                                o.put("q", s.query);
                                o.put("lbl", s.label);
                                data = o.toString();
                            } catch (Exception ignored) {
                            }
                        } else {
                            if (savedSearches.isEmpty()) {
                                Toast.makeText(getContext(), R.string.dialog_add_edit_tile_toast_no_saved_searches,
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else if ("useful".equals(type)) {
                        type = Tile.ACTION_ACTIVITY;
                        data = "pl.kejlo.zutnik.UsefulLinksActivity";
                    } else if ("about".equals(type)) {
                        type = Tile.ACTION_ACTIVITY;
                        data = "pl.kejlo.zutnik.AboutActivity";
                    }

                    if (tileToEdit != null) {
                        tileToEdit.title = title;
                        tileToEdit.description = desc;
                        tileToEdit.actionType = type;
                        tileToEdit.actionData = data;
                        tileToEdit.color = selectedColor; // Update color

                        // Clear Localization IDs as user provided manual overrides
                        tileToEdit.titleResId = 0;
                        tileToEdit.descResId = 0;

                        if (listener != null)
                            listener.onTileSaved(tileToEdit);
                    } else {
                        Tile t = new Tile();
                        t.id = System.currentTimeMillis();
                        t.title = title;
                        t.description = desc;
                        t.actionType = type;
                        t.actionData = data;
                        t.color = selectedColor; // Set color
                        t.colSpan = 2; // Default size 2x2
                        t.rowSpan = 2;
                        if (listener != null)
                            listener.onTileSaved(t);
                    }
                })
                .setNegativeButton(R.string.dialog_add_edit_tile_btn_cancel, (dialog, id) -> dialog.dismiss());

        return builder.create();
    }

    private int dpToPx(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return (int) (dp * d);
    }
}
