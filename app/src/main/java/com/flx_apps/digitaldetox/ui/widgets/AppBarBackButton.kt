package com.flx_apps.digitaldetox.ui.widgets

import com.flx_apps.digitaldetox.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import com.flx_apps.digitaldetox.ui.screens.nav_host.NavViewModel

@Composable
fun AppBarBackButton(navViewModel: NavViewModel = NavViewModel.navViewModel()) {
    IconButton(onClick = {
        navViewModel.onBackPress()
    }) {
        Icon(
            imageVector = Icons.Default.ArrowBack, contentDescription = stringResource(id = R.string.action_back)
        )
    }
}