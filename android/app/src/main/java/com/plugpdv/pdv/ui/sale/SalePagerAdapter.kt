package com.plugpdv.pdv.ui.sale

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class SalePagerAdapter(
    fragmentActivity: FragmentActivity, 
    private val fragments: List<Fragment>
) : FragmentStateAdapter(fragmentActivity) {

    override fun createFragment(position: Int): Fragment {
        return fragments[position]
    }

    override fun getItemCount(): Int = fragments.size
}
