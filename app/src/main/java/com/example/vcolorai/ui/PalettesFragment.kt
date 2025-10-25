package com.example.vcolorai.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.vcolorai.databinding.FragmentPalettesBinding

class PalettesFragment : Fragment() {

    private var _binding: FragmentPalettesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPalettesBinding.inflate(inflater, container, false)
        binding.textView.text = "Мои палитры"
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
