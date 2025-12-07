package pl.kejlo.mzutv2;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

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
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
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
        EditText inputColorHex = view.findViewById(R.id.inputColorHex);
        
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
            0xFFE0E0E0  // Light
        };
        
        for (int color : palette) {
            View cView = new View(requireContext());
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(dpToPx(36), dpToPx(36));
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
                if (color == 0) {
                    inputColorHex.setText("");
                } else {
                    inputColorHex.setText(String.format("#%06X", (0xFFFFFF & color)));
                }
            });
            containerColors.addView(cView);
        }
        
        // Manual Hex Input Listener
        inputColorHex.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String hex = s.toString().trim();
                if (hex.isEmpty()) {
                    selectedColor = 0;
                    return;
                }
                try {
                    selectedColor = android.graphics.Color.parseColor(hex);
                } catch (IllegalArgumentException e) {
                    // Invalid color, ignore
                }
            }
        });

        // Populate Spinner
        List<String> actionLabels = new ArrayList<>();
        List<String> actionValues = new ArrayList<>();
        
        // Define actions
        actionLabels.add("Plan zajęć"); actionValues.add(Tile.ACTION_PLAN);
        actionLabels.add("Oceny"); actionValues.add(Tile.ACTION_GRADES);
        actionLabels.add("Informacje"); actionValues.add(Tile.ACTION_INFO);
        actionLabels.add("Aktualności"); actionValues.add(Tile.ACTION_NEWS);
        actionLabels.add("Najnowsza Wiadomość (Auto)"); actionValues.add(Tile.ACTION_NEWS_LATEST);
        actionLabels.add("Zapisane Wyszukiwanie Planu"); actionValues.add(Tile.ACTION_PLAN_SEARCH);
        actionLabels.add("Przydatne linki"); actionValues.add("useful"); // Mapped later
        actionLabels.add("O aplikacji"); actionValues.add("about");
        actionLabels.add("Własny Link URL"); actionValues.add(Tile.ACTION_URL);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, actionLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAction.setAdapter(adapter);
        
        // Load Saved Searches
        List<PlanRepository.SavedSearch> savedSearches = PlanRepository.loadSavedSearches(requireContext());
        List<String> savedLabels = new ArrayList<>();
        if (savedSearches.isEmpty()) {
            savedLabels.add("(Brak zapisanych wyszukiwań)");
        } else {
            for (PlanRepository.SavedSearch s : savedSearches) {
                savedLabels.add(s.label + " (" + s.catLabel + ": " + s.query + ")");
            }
        }
        ArrayAdapter<String> savedAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, savedLabels);
        savedAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
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
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        // Pre-fill if editing
        if (tileToEdit != null) {
            builder.setTitle("Edytuj kafelek");
            etTitle.setText(tileToEdit.title);
            etDesc.setText(tileToEdit.description);
            
            // Color pre-fill
            selectedColor = tileToEdit.color;
            if (selectedColor != 0) {
                 inputColorHex.setText(String.format("#%06X", (0xFFFFFF & selectedColor)));
            }
            
            String currentType = tileToEdit.actionType;
            if (Tile.ACTION_ACTIVITY.equals(currentType) && tileToEdit.actionData != null) {
                if (tileToEdit.actionData.contains("UsefulLinksActivity")) currentType = "useful";
                else if (tileToEdit.actionData.contains("AboutActivity")) currentType = "about";
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
                     for(int i=0; i<savedSearches.size(); i++) {
                         PlanRepository.SavedSearch s = savedSearches.get(i);
                         if (s.catKey.equals(key) && s.query.equals(q)) {
                             spinnerSavedParams.setSelection(i);
                             break;
                         }
                     }
                 } catch(Exception ignored){}
            }
        } else {
            builder.setTitle("Nowy kafelek");
        }

        builder.setView(view)
                .setPositiveButton("Zapisz", (dialog, id) -> {
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
                            } catch (Exception ignored) {}
                        } else {
                            if (savedSearches.isEmpty()) {
                                Toast.makeText(getContext(), "Brak zapisanych wyszukiwań!", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else if ("useful".equals(type)) {
                        type = Tile.ACTION_ACTIVITY;
                        data = "pl.kejlo.mzutv2.UsefulLinksActivity";
                    } else if ("about".equals(type)) {
                        type = Tile.ACTION_ACTIVITY;
                        data = "pl.kejlo.mzutv2.AboutActivity";
                    }
                    
                    // Final Color check from input in case user typed but didn't press enter/etc
                    // Actually TextWatcher handles it.
                    
                    if (tileToEdit != null) {
                        tileToEdit.title = title;
                        tileToEdit.description = desc;
                        tileToEdit.actionType = type;
                        tileToEdit.actionData = data;
                        tileToEdit.color = selectedColor; // Update color
                        if (listener != null) listener.onTileSaved(tileToEdit);
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
                        if (listener != null) listener.onTileSaved(t);
                    }
                })
                .setNegativeButton("Anuluj", (dialog, id) -> dialog.dismiss());

        return builder.create();
    }
    
    private int dpToPx(int dp) {
        float d = getResources().getDisplayMetrics().density;
        return (int)(dp * d);
    }
}
