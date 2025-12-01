package ricassiocosta.me.valv5.viewmodel;

import androidx.lifecycle.ViewModel;

import ricassiocosta.me.valv5.data.DirHash;
import ricassiocosta.me.valv5.data.Password;

public class PasswordViewModel extends ViewModel {
    private static final String TAG = "PasswordViewModel";

    private Password password;

    public boolean isLocked() {
        initPassword();
        return password.getPassword() == null;
    }

    private void initPassword() {
        if (password == null) {
            this.password = Password.getInstance();
        }
    }

    public void setPassword(char[] password) {
        initPassword();
        this.password.setPassword(password);
    }

    public char[] getPassword() {
        initPassword();
        return password.getPassword();
    }

    public void setDirHash(DirHash dirHash) {
        initPassword();
        this.password.setDirHash(dirHash);
    }

}
